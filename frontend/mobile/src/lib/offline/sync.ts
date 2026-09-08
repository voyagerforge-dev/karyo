import { api, ApiError, isOfflineError } from '@/lib/api-client'
import { listPending, remove, markFailed, type QueuedOp } from '@/lib/offline/op-queue'

let draining = false
const listeners = new Set<() => void>()
export function onSyncChange(l: () => void): () => void { listeners.add(l); return () => { listeners.delete(l) } }
const notify = () => listeners.forEach((l) => l())
/** Notify sync-change listeners from outside this module (e.g. after an enqueue). */
export const notifyChange = (): void => notify()

/**
 * 409 type slugs that mean "this queued op was already applied server-side" PER op type -- there
 * is no dedicated idempotent-replay slug, so the allowlist is the op's own state-guard slug.
 * A pick-confirm replay is a 422 (never dropped here); its only 409 is the genuine bulk-order
 * refusal, which must surface. Any 409 not listed for its op type is kept and marked failed.
 */
const DROPPABLE_409: Record<QueuedOp['type'], readonly string[]> = {
  'pick-confirm': [],
  'transport-start': ['invalid-state-transition'],
  'transport-complete': ['invalid-state-transition'],
  'count-submit': ['count-invalid-state'],
  'count-location-empty': ['count-invalid-state'],
  release: ['invalid-state-transition', 'count-invalid-state', 'fulfillment-validation-failed'],
}

const slugOf = (type: string): string => type.split('/').pop() ?? type

/** Replay pending ops. 2xx -> remove; 409 whose slug is droppable for the op's type -> remove
 *  (already-applied); other HTTP error (incl. any non-droppable 409) -> mark failed; network
 *  error -> stop. */
export async function drain(): Promise<void> {
  if (draining || !navigator.onLine) return
  draining = true
  try {
    for (const op of await listPending()) {
      try {
        await api.post(op.url, op.body)
        await remove(op.id); notify()
      } catch (e) {
        if (isOfflineError(e)) break
        if (e instanceof ApiError && e.problem.status === 409 && (DROPPABLE_409[op.type] ?? []).includes(slugOf(e.problem.type))) {
          await remove(op.id); notify()
        } else { await markFailed(op.id, e instanceof ApiError ? e.problem.detail : 'Sync failed'); notify() }
      }
    }
  } finally { draining = false }
}

/** Replay on reconnect + when the app returns to the foreground. Returns a cleanup fn. */
export function startSyncTriggers(): () => void {
  const onOnline = () => { void drain() }
  const onVisible = () => { if (document.visibilityState === 'visible') void drain() }
  window.addEventListener('online', onOnline)
  document.addEventListener('visibilitychange', onVisible)
  void drain()
  return () => { window.removeEventListener('online', onOnline); document.removeEventListener('visibilitychange', onVisible) }
}

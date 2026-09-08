export interface QueuedOp {
  id: string
  type: 'pick-confirm' | 'transport-start' | 'transport-complete' | 'count-submit' | 'count-location-empty' | 'release'
  method: 'POST'
  url: string
  body?: unknown
  taskRef?: string
  label: string
  createdAt: number
  /** Monotonic insertion counter — used to guarantee FIFO replay order regardless of IDB key order. */
  seq?: number
  status: 'pending' | 'failed'
  error?: string
}

const DB = 'karyo-offline'
const STORE = 'pending-ops'

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1)
    req.onupgradeneeded = () => { req.result.createObjectStore(STORE, { keyPath: 'id' }) }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function run<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return openDB().then((db) => new Promise<T>((resolve, reject) => {
    const req = fn(db.transaction(STORE, mode).objectStore(STORE))
    req.onsuccess = () => { db.close(); resolve(req.result) }
    req.onerror = () => { db.close(); reject(req.error) }
  }))
}

/** Enqueue an op, assigning a monotonic seq so drain() replays in insertion (FIFO) order. */
export const enqueue = async (op: QueuedOp): Promise<void> => {
  const all = await run<QueuedOp[]>('readonly', (s) => s.getAll())
  const maxSeq = all.reduce((m, o) => Math.max(m, o.seq ?? 0), 0)
  await run('readwrite', (s) => s.add({ ...op, seq: maxSeq + 1 }))
}

/** All stored ops, sorted by seq ascending (FIFO). */
export const listAll = (): Promise<QueuedOp[]> =>
  run<QueuedOp[]>('readonly', (s) => s.getAll()).then((ops) => ops.sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0)))

export const listPending = async (): Promise<QueuedOp[]> => (await listAll()).filter((o) => o.status === 'pending')
export const listFailed = async (): Promise<QueuedOp[]> => (await listAll()).filter((o) => o.status === 'failed')
export const remove = (id: string): Promise<void> => run('readwrite', (s) => s.delete(id)).then(() => undefined)
export const countPending = async (): Promise<number> => (await listPending()).length

export async function markFailed(id: string, error: string): Promise<void> {
  const all = await listAll()
  const op = all.find((o) => o.id === id)
  if (!op) return
  op.status = 'failed'; op.error = error
  await run('readwrite', (s) => s.put(op))
}

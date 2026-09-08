import type { ReceiveEntry } from '@/lib/work-api'

/** `loading` is handled outside the reducer (react-query isLoading), same convention as
 *  count/move/pick — the reducer only owns the states that follow a loaded entry. */
export type ReceivePhase = 'pick-line' | 'qty' | 'confirm' | 'done'

export interface ReceiveState {
  phase: ReceivePhase
  /** Index into entry.lines of the line currently being worked (qty/confirm phases); -1 when
   *  no line is selected (pick-line phase). */
  lineIndex: number
  qty: number
  unitLoadLabel: string
  error?: string
  /** asnLineIds confirmed or skipped this session — removed from the open-line list. */
  resolvedLineIds: number[]
}

export type ReceiveEvent =
  | { type: 'SELECT_LINE'; lineIndex: number }
  | { type: 'SET_QTY'; qty: number }
  | { type: 'SET_LABEL'; label: string }
  | { type: 'REVIEW' }
  | { type: 'CONFIRM_SUCCESS' }
  | { type: 'CONFIRM_ERROR'; message: string }
  | { type: 'SKIP_LINE'; lineIndex: number }
  | { type: 'FINISH' }

export const initReceive: ReceiveState = { phase: 'pick-line', lineIndex: -1, qty: 0, unitLoadLabel: '', resolvedLineIds: [] }

/** Expected lines not yet confirmed or skipped this session. */
export function openLines(entry: ReceiveEntry, s: ReceiveState) {
  return entry.lines.filter((l) => !s.resolvedLineIds.includes(l.asnLineId))
}

function resolve(entry: ReceiveEntry, s: ReceiveState, lineIndex: number): ReceiveState {
  const line = entry.lines[lineIndex]
  const resolvedLineIds = [...s.resolvedLineIds, line.asnLineId]
  const remaining = entry.lines.filter((l) => !resolvedLineIds.includes(l.asnLineId))
  return { ...s, resolvedLineIds, phase: remaining.length === 0 ? 'done' : 'pick-line', lineIndex: -1, qty: 0, unitLoadLabel: '', error: undefined }
}

export function receiveReducer(entry: ReceiveEntry, s: ReceiveState, e: ReceiveEvent): ReceiveState {
  switch (e.type) {
    case 'SELECT_LINE': {
      const line = entry.lines[e.lineIndex]
      // qty defaults to the line's remaining (expected) amount.
      return { ...s, phase: 'qty', lineIndex: e.lineIndex, qty: line.remainingAmount, unitLoadLabel: '', error: undefined }
    }
    case 'SET_QTY': return { ...s, qty: e.qty }
    case 'SET_LABEL': return { ...s, unitLoadLabel: e.label }
    case 'REVIEW': return { ...s, phase: 'confirm', error: undefined }
    case 'CONFIRM_SUCCESS': return resolve(entry, s, s.lineIndex)
    case 'CONFIRM_ERROR': return { ...s, phase: 'confirm', error: e.message }
    case 'SKIP_LINE': return resolve(entry, s, e.lineIndex)
    case 'FINISH': return { ...s, phase: 'done' }
    default: return s
  }
}

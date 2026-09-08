import type { CountEntry } from '@/lib/work-api'

export type CountPhase = 'location' | 'item' | 'qty' | 'done'
export interface CountState { phase: CountPhase; lineIndex: number; qty: number; counts: Record<number, number>; error?: string }
export type CountEvent =
  | { type: 'LOCATION_OK' } | { type: 'ITEM_OK' } | { type: 'SET_QTY'; qty: number }
  | { type: 'MISMATCH'; what: string } | { type: 'NEXT' } | { type: 'SUBMITTED' }

export const initCount: CountState = { phase: 'location', lineIndex: 0, qty: 0, counts: {} }

export function countReducer(entry: CountEntry, s: CountState, e: CountEvent): CountState {
  switch (e.type) {
    case 'MISMATCH': return { ...s, error: `Wrong ${e.what} — try again` }
    case 'LOCATION_OK': return { ...s, phase: 'item', error: undefined }
    case 'ITEM_OK': return { ...s, phase: 'qty', error: undefined }
    case 'SET_QTY': return { ...s, qty: e.qty }
    case 'NEXT': { // advance to next line (only called for non-last lines)
      const lineId = entry.lines[s.lineIndex].lineId
      return { ...s, counts: { ...s.counts, [lineId]: s.qty }, lineIndex: s.lineIndex + 1, phase: 'item', qty: 0, error: undefined }
    }
    case 'SUBMITTED': return { ...s, phase: 'done' }
    default: return s
  }
}

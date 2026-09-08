import type { PickOrder } from '@/lib/work-api'

export type Phase = 'location' | 'item' | 'qty' | 'done'
export interface PickState { lineIndex: number; phase: Phase; qty: number; error?: string }
export type PickEvent =
  | { type: 'LOCATION_OK' } | { type: 'ITEM_OK' } | { type: 'MISMATCH'; what: string }
  | { type: 'SET_QTY'; qty: number } | { type: 'CONFIRMED' }

export function initPick(order: PickOrder): PickState {
  const open = order.picks.findIndex((p) => p.state < 600) // < PICKED
  const idx = open === -1 ? order.picks.length : open
  return { lineIndex: idx, phase: idx >= order.picks.length ? 'done' : 'location', qty: idx < order.picks.length ? order.picks[idx].plannedAmount : 0 }
}

export function reducer(order: PickOrder, s: PickState, e: PickEvent): PickState {
  switch (e.type) {
    case 'MISMATCH': return { ...s, error: `Wrong ${e.what} — try again` }
    case 'LOCATION_OK': return { ...s, phase: 'item', error: undefined }
    case 'ITEM_OK': return { ...s, phase: 'qty', error: undefined }
    case 'SET_QTY': return { ...s, qty: e.qty }
    case 'CONFIRMED': {
      const nextIdx = s.lineIndex + 1
      if (nextIdx >= order.picks.length) return { ...s, phase: 'done' }
      return { lineIndex: nextIdx, phase: 'location', qty: order.picks[nextIdx].plannedAmount }
    }
    default: return s
  }
}

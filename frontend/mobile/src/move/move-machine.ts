export type MovePhase = 'source' | 'destination' | 'done'
export interface MoveState { phase: MovePhase; destConfirmed: boolean; error?: string }
export type MoveEvent =
  | { type: 'SOURCE_OK' } | { type: 'DEST_OK' } | { type: 'MISMATCH'; what: string } | { type: 'COMPLETED' }

export const initMove: MoveState = { phase: 'source', destConfirmed: false }

export function moveReducer(s: MoveState, e: MoveEvent): MoveState {
  switch (e.type) {
    case 'MISMATCH': return { ...s, error: `Wrong ${e.what} — try again` }
    case 'SOURCE_OK': return { ...s, phase: 'destination', error: undefined }
    case 'DEST_OK': return { ...s, destConfirmed: true, error: undefined }
    case 'COMPLETED': return { ...s, phase: 'done' }
    default: return s
  }
}

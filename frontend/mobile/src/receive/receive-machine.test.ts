import { initReceive, receiveReducer, openLines } from '@/receive/receive-machine'
import type { ReceiveEntry } from '@/lib/work-api'

const entry: ReceiveEntry = {
  id: 1, receiptNumber: 'GR-1', state: 500,
  dockLocationId: 9, dockLocationName: 'DOCK-1', pausedAt: null, receivedCount: 0,
  lines: [
    { asnLineId: 11, asnNumber: 'ASN-1', itemDataNumber: 'SKU-A', remainingAmount: 5 },
    { asnLineId: 12, asnNumber: 'ASN-1', itemDataNumber: 'SKU-B', remainingAmount: 3 },
  ],
}

test('select-line defaults qty to the line remaining amount and moves to qty', () => {
  const s = receiveReducer(entry, initReceive, { type: 'SELECT_LINE', lineIndex: 0 })
  expect(s.phase).toBe('qty')
  expect(s.lineIndex).toBe(0)
  expect(s.qty).toBe(5)
})

test('set-qty overrides the default', () => {
  let s = receiveReducer(entry, initReceive, { type: 'SELECT_LINE', lineIndex: 0 })
  s = receiveReducer(entry, s, { type: 'SET_QTY', qty: 2 })
  expect(s.qty).toBe(2)
})

test('set-label records the scanned unit-load label', () => {
  let s = receiveReducer(entry, initReceive, { type: 'SELECT_LINE', lineIndex: 0 })
  s = receiveReducer(entry, s, { type: 'SET_LABEL', label: 'UL-9001' })
  expect(s.unitLoadLabel).toBe('UL-9001')
})

test('review moves qty -> confirm', () => {
  let s = receiveReducer(entry, initReceive, { type: 'SELECT_LINE', lineIndex: 0 })
  s = receiveReducer(entry, s, { type: 'REVIEW' })
  expect(s.phase).toBe('confirm')
})

test('confirm-success with lines remaining resolves the line and returns to pick-line', () => {
  let s = receiveReducer(entry, initReceive, { type: 'SELECT_LINE', lineIndex: 0 })
  s = receiveReducer(entry, s, { type: 'SET_QTY', qty: 5 })
  s = receiveReducer(entry, s, { type: 'REVIEW' })
  s = receiveReducer(entry, s, { type: 'CONFIRM_SUCCESS' })
  expect(s.phase).toBe('pick-line')
  expect(s.resolvedLineIds).toEqual([11])
  expect(openLines(entry, s)).toEqual([entry.lines[1]])
  // selection state cleared for the next line
  expect(s.lineIndex).toBe(-1)
  expect(s.qty).toBe(0)
  expect(s.unitLoadLabel).toBe('')
})

test('confirm-success on the last remaining line reaches done', () => {
  let s = { ...initReceive, resolvedLineIds: [11] }
  s = receiveReducer(entry, s, { type: 'SELECT_LINE', lineIndex: 1 })
  s = receiveReducer(entry, s, { type: 'REVIEW' })
  s = receiveReducer(entry, s, { type: 'CONFIRM_SUCCESS' })
  expect(s.phase).toBe('done')
  expect(openLines(entry, s)).toEqual([])
})

test('confirm-error sets an error and stays in confirm for retry (qty/label preserved)', () => {
  let s = receiveReducer(entry, initReceive, { type: 'SELECT_LINE', lineIndex: 0 })
  s = receiveReducer(entry, s, { type: 'SET_QTY', qty: 4 })
  s = receiveReducer(entry, s, { type: 'REVIEW' })
  s = receiveReducer(entry, s, { type: 'CONFIRM_ERROR', message: 'Could not receive this line — retry' })
  expect(s.phase).toBe('confirm')
  expect(s.error).toBe('Could not receive this line — retry')
  expect(s.qty).toBe(4)
})

test('skip-line resolves the line without confirming and advances to the next open line', () => {
  const s = receiveReducer(entry, initReceive, { type: 'SKIP_LINE', lineIndex: 0 })
  expect(s.phase).toBe('pick-line')
  expect(s.resolvedLineIds).toEqual([11])
  expect(openLines(entry, s)).toEqual([entry.lines[1]])
})

test('skip-line on the last remaining line reaches done', () => {
  const s = receiveReducer(entry, { ...initReceive, resolvedLineIds: [11] }, { type: 'SKIP_LINE', lineIndex: 1 })
  expect(s.phase).toBe('done')
})

test('finish is a manual done affordance even with lines still open', () => {
  const s = receiveReducer(entry, initReceive, { type: 'FINISH' })
  expect(s.phase).toBe('done')
  expect(s.resolvedLineIds).toEqual([]) // untouched lines are simply abandoned, not marked resolved
})

test('openLines returns all entry lines when nothing is resolved yet', () => {
  expect(openLines(entry, initReceive)).toEqual(entry.lines)
})

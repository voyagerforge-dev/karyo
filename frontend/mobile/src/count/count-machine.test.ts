import { initCount, countReducer } from '@/count/count-machine'

const entry = { id: 1, orderNumber: 'CO-1', locationName: 'R-03', lines: [
  { lineId: 11, itemDataNumber: 'SKU-A' }, { lineId: 12, itemDataNumber: 'SKU-B' },
] }

test('location -> item -> qty -> NEXT records count and advances to next item', () => {
  let s = initCount
  s = countReducer(entry, s, { type: 'LOCATION_OK' }); expect(s.phase).toBe('item')
  s = countReducer(entry, s, { type: 'ITEM_OK' }); expect(s.phase).toBe('qty')
  s = countReducer(entry, s, { type: 'SET_QTY', qty: 7 })
  s = countReducer(entry, s, { type: 'NEXT' })
  expect(s).toMatchObject({ lineIndex: 1, phase: 'item', qty: 0 })
  expect(s.counts).toEqual({ 11: 7 })
})

test('qty can be 0 (expected-but-absent)', () => {
  const s = countReducer(entry, { ...initCount, phase: 'qty' }, { type: 'SET_QTY', qty: 0 })
  expect(s.qty).toBe(0)
})

test('mismatch sets error without advancing', () => {
  const s = countReducer(entry, { ...initCount, phase: 'item' }, { type: 'MISMATCH', what: 'item' })
  expect(s.phase).toBe('item'); expect(s.error).toMatch(/Wrong item/)
})

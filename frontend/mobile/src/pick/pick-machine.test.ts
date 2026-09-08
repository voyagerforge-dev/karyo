import { initPick, reducer, type PickState } from '@/pick/pick-machine'

const order = { id: 1, pickOrderNumber: 'PO-1', deliveryOrderNumber: 'DO-1', state: 100, bulk: false, picks: [
  { id: 11, itemDataNumber: 'SKU-A', sourceStockUnitId: 99, plannedAmount: 5, pickedAmount: 0, state: 100 },
  { id: 12, itemDataNumber: 'SKU-B', sourceStockUnitId: 98, plannedAmount: 3, pickedAmount: 0, state: 100 },
] }

test('init starts at first open line, location phase, qty prefilled', () => {
  const s = initPick(order)
  expect(s).toMatchObject({ lineIndex: 0, phase: 'location', qty: 5 })
})

test('location->item->qty->confirm advances to next line; last line -> done', () => {
  let s: PickState = initPick(order)
  s = reducer(order, s, { type: 'LOCATION_OK' }); expect(s.phase).toBe('item')
  s = reducer(order, s, { type: 'ITEM_OK' }); expect(s.phase).toBe('qty')
  s = reducer(order, s, { type: 'CONFIRMED' }); expect(s).toMatchObject({ lineIndex: 1, phase: 'location', qty: 3 })
  s = reducer(order, s, { type: 'LOCATION_OK' }); s = reducer(order, s, { type: 'ITEM_OK' })
  s = reducer(order, s, { type: 'CONFIRMED' }); expect(s.phase).toBe('done')
})

test('mismatch sets an error and does not advance phase', () => {
  const s = reducer(order, initPick(order), { type: 'MISMATCH', what: 'location' })
  expect(s.phase).toBe('location'); expect(s.error).toMatch(/Wrong location/)
})

test('init skips already-PICKED lines', () => {
  const partial = { ...order, picks: [{ ...order.picks[0], state: 600, pickedAmount: 5 }, order.picks[1]] }
  expect(initPick(partial)).toMatchObject({ lineIndex: 1, phase: 'location', qty: 3 })
})

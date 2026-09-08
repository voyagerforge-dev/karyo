import { initMove, moveReducer } from '@/move/move-machine'

test('source -> destination -> dest confirmed -> done', () => {
  let s = initMove
  expect(s.phase).toBe('source')
  s = moveReducer(s, { type: 'SOURCE_OK' }); expect(s.phase).toBe('destination')
  s = moveReducer(s, { type: 'DEST_OK' }); expect(s.destConfirmed).toBe(true)
  s = moveReducer(s, { type: 'COMPLETED' }); expect(s.phase).toBe('done')
})

test('mismatch sets error without advancing', () => {
  const s = moveReducer(initMove, { type: 'MISMATCH', what: 'unit-load' })
  expect(s.phase).toBe('source'); expect(s.error).toMatch(/Wrong unit-load/)
})

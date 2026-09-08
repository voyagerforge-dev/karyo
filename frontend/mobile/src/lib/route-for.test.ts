import { routeFor } from '@/lib/work-api'

test('routes each work-type to its screen', () => {
  expect(routeFor('PICK', 'PICK:1')).toBe('/pick/PICK%3A1')
  expect(routeFor('COUNT', 'COUNT:2')).toBe('/count/COUNT%3A2')
  expect(routeFor('PUTAWAY', 'PUTAWAY:3')).toBe('/move/PUTAWAY%3A3')
  expect(routeFor('MOVE', 'MOVE:4')).toBe('/move/MOVE%3A4')
  expect(routeFor('REPLENISH', 'REPLENISH:5')).toBe('/move/REPLENISH%3A5')
  expect(routeFor('RECEIVE', 'RECEIVE:6')).toBe('/receive/RECEIVE%3A6')
  expect(routeFor('TRANSFER', 'TRANSFER:8')).toBe('/move/TRANSFER%3A8')
  // Cross-docking sprint (Task 3 fix-round): CROSS_DOCK now joins the work-inbox and rides the
  // same 'move' execution screen as PUTAWAY/MOVE/REPLENISH/TRANSFER.
  expect(routeFor('CROSS_DOCK', 'CROSS_DOCK:9')).toBe('/move/CROSS_DOCK%3A9')
})

// Highest-risk finding from the inbound-completion recon: an unrecognized workType used to fall
// through to the move screen silently, misrepresenting an unknown task as a transport move. It
// must now fall back to the inbox, not any execution screen.
test('an unrecognized work type falls back to the inbox, not a screen', () => {
  expect(routeFor('SOMETHING_NEW', 'SOMETHING_NEW:7')).toBe('/')
})

import { drain } from '@/lib/offline/sync'
import { enqueue, listAll, type QueuedOp } from '@/lib/offline/op-queue'
import { api, ApiError } from '@/lib/api-client'

function op(id: string, type: QueuedOp['type'] = 'pick-confirm'): QueuedOp {
  return { id, type, method: 'POST', url: `/api/v1/picks/${id}/confirm`, body: {}, label: `c${id}`, createdAt: 1, status: 'pending' }
}
function setOnline(v: boolean) { Object.defineProperty(navigator, 'onLine', { configurable: true, value: v }) }
const apiErr = (status: number, slug = 'about:blank') =>
  new ApiError({ type: slug === 'about:blank' ? slug : `https://karyo.com/errors/${slug}`, title: 't', status, detail: `d${status}` })

beforeEach(async () => {
  await new Promise((r) => { const req = indexedDB.deleteDatabase('karyo-offline'); req.onsuccess = req.onerror = () => r(null) })
  vi.restoreAllMocks(); setOnline(true)
})

test('2xx removes the op from the queue', async () => {
  await enqueue(op('1'))
  vi.spyOn(api, 'post').mockResolvedValue({} as never)
  await drain()
  expect(await listAll()).toHaveLength(0)
})

test('409 invalid-state-transition on a transport op is already-applied and removed', async () => {
  await enqueue(op('1', 'transport-start'))
  vi.spyOn(api, 'post').mockRejectedValue(apiErr(409, 'invalid-state-transition'))
  await drain()
  expect(await listAll()).toHaveLength(0)
})

test('409 fulfillment-validation-failed on a pick-confirm is kept and marked failed', async () => {
  await enqueue(op('1', 'pick-confirm'))
  vi.spyOn(api, 'post').mockRejectedValue(apiErr(409, 'fulfillment-validation-failed'))
  await drain()
  const all = await listAll()
  expect(all).toHaveLength(1)
  expect(all[0]).toMatchObject({ status: 'failed', error: 'd409' })
})

test('409 with an unrecognized or absent type slug is kept and marked failed', async () => {
  await enqueue(op('1', 'transport-start'))
  vi.spyOn(api, 'post').mockRejectedValue(apiErr(409, 'about:blank'))
  await drain()
  const all = await listAll()
  expect(all).toHaveLength(1)
  expect(all[0]).toMatchObject({ status: 'failed', error: 'd409' })
})

test('409 count-invalid-state on a count op is removed; on a transport op it is kept', async () => {
  await enqueue(op('1', 'count-submit'))
  await enqueue(op('2', 'transport-start'))
  vi.spyOn(api, 'post').mockRejectedValue(apiErr(409, 'count-invalid-state'))
  await drain()
  const all = await listAll()
  expect(all).toHaveLength(1)
  expect(all[0]).toMatchObject({ id: '2', status: 'failed', error: 'd409' })
})

test('a hard error (403) marks the op failed (kept, surfaced)', async () => {
  await enqueue(op('1'))
  vi.spyOn(api, 'post').mockRejectedValue(apiErr(403))
  await drain()
  const all = await listAll()
  expect(all).toHaveLength(1)
  expect(all[0]).toMatchObject({ status: 'failed', error: 'd403' })
})

test('a network failure mid-drain leaves the op pending', async () => {
  await enqueue(op('1'))
  vi.spyOn(api, 'post').mockRejectedValue(new TypeError('Failed to fetch'))
  await drain()
  const all = await listAll()
  expect(all[0].status).toBe('pending')
})

test('drain replays ops in insertion (FIFO) order regardless of UUID key order', async () => {
  // Enqueue start first with id 'zzz-start' (sorts LAST in IDB key order).
  // Enqueue complete second with id 'aaa-complete' (sorts FIRST in IDB key order).
  // Without seq-based FIFO, getAll() would hand drain() [aaa-complete, zzz-start],
  // replay complete before start → 409 treated as already-applied → move lost silently.
  // With seq sorting: start (seq=1) must be called before complete (seq=2).
  const callOrder: string[] = []
  vi.spyOn(api, 'post').mockImplementation((url) => { callOrder.push(url as string); return Promise.resolve({} as never) })

  await enqueue({ id: 'zzz-start',    type: 'transport-start',    method: 'POST', url: '/api/v1/transport-orders/1/start',    body: {}, label: 'start',    createdAt: 1, status: 'pending' })
  await enqueue({ id: 'aaa-complete', type: 'transport-complete', method: 'POST', url: '/api/v1/transport-orders/1/complete', body: {}, label: 'complete', createdAt: 1, status: 'pending' })

  await drain()

  expect(callOrder).toEqual([
    '/api/v1/transport-orders/1/start',
    '/api/v1/transport-orders/1/complete',
  ])
})

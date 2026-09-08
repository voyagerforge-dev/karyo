import { workApi } from '@/lib/work-api'
import { listAll } from '@/lib/offline/op-queue'
import { api } from '@/lib/api-client'

function setOnline(v: boolean) { Object.defineProperty(navigator, 'onLine', { configurable: true, value: v }) }

beforeEach(async () => {
  await new Promise((r) => { const req = indexedDB.deleteDatabase('karyo-offline'); req.onsuccess = req.onerror = () => r(null) })
  vi.restoreAllMocks()
})
afterEach(() => setOnline(true))

test('offline confirmPick enqueues and resolves (does not throw)', async () => {
  setOnline(false)
  const spy = vi.spyOn(api, 'post')
  await expect(workApi.confirmPick(42, 5)).resolves.toBeUndefined()
  expect(spy).not.toHaveBeenCalled()
  const q = await listAll()
  expect(q).toHaveLength(1)
  expect(q[0]).toMatchObject({ type: 'pick-confirm', url: '/api/v1/picks/42/confirm', body: { pickedAmount: 5 }, status: 'pending' })
})

test('online confirmPick calls api.post and does not enqueue', async () => {
  setOnline(true)
  const spy = vi.spyOn(api, 'post').mockResolvedValue({} as never)
  await workApi.confirmPick(42, 5)
  expect(spy).toHaveBeenCalledWith('/api/v1/picks/42/confirm', { pickedAmount: 5 })
  expect(await listAll()).toHaveLength(0)
})

test('bulkConfirm posts directly via api.post, even while offline, and never enqueues', async () => {
  setOnline(false)
  const spy = vi.spyOn(api, 'post').mockResolvedValue({ filledSlices: 2, shortSlices: 1 } as never)
  const result = await workApi.bulkConfirm(7, { sourceStockUnitId: 3, pickedAmount: 27 })
  expect(spy).toHaveBeenCalledWith('/api/v1/pick-orders/7/bulk-confirm', { sourceStockUnitId: 3, pickedAmount: 27 })
  expect(result).toEqual({ filledSlices: 2, shortSlices: 1 })
  expect(await listAll()).toHaveLength(0)
})

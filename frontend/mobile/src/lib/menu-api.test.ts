import { describe, expect, it, vi, beforeEach } from 'vitest'

const get = vi.fn()
const post = vi.fn()
vi.mock('@/lib/api-client', () => ({
  api: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a) },
  ApiError: class ApiError extends Error {
    problem: { type: string; title: string; status: number; detail: string }
    constructor(problem: { type: string; title: string; status: number; detail: string }) { super(problem.detail); this.problem = problem }
  },
  toast: { error: vi.fn() },
}))

const mockKeycloak = vi.hoisted(() => ({ tokenParsed: undefined as { preferred_username?: string } | undefined }))
vi.mock('@/lib/keycloak', () => ({ keycloak: mockKeycloak }))

const notFoundProblem = { type: 'about:blank', title: 'Not Found', status: 404, detail: 'not found' }

import { menuApi } from '@/lib/menu-api'

describe('resolveScan', () => {
  beforeEach(() => get.mockReset())

  it('unit load label wins first', async () => {
    get.mockResolvedValueOnce({ id: 7, labelId: 'UL-1', stockUnits: [] })
    const hit = await menuApi.resolveScan('UL-1')
    expect(hit.kind).toBe('unitLoad')
    expect(get).toHaveBeenCalledWith('/api/v1/unit-loads/by-label/UL-1')
  })

  it('falls through 404 to location lookup', async () => {
    const { ApiError } = await import('@/lib/api-client')
    get.mockRejectedValueOnce(new ApiError(notFoundProblem))
    get.mockResolvedValueOnce({ id: 3, name: 'A-01-01' })
    const hit = await menuApi.resolveScan('A-01-01')
    expect(hit.kind).toBe('location')
    expect(get).toHaveBeenCalledWith('/api/v1/locations/by-scan-code/A-01-01')
  })

  it('nothing found reports none', async () => {
    const { ApiError } = await import('@/lib/api-client')
    get.mockRejectedValueOnce(new ApiError(notFoundProblem))
    get.mockRejectedValueOnce(new ApiError(notFoundProblem))
    const hit = await menuApi.resolveScan('GHOST')
    expect(hit).toEqual({ kind: 'none', code: 'GHOST' })
  })

  it('a non-ApiError failure from unit-load lookup propagates without falling through', async () => {
    get.mockRejectedValueOnce(new Error('network down'))
    await expect(menuApi.resolveScan('UL-1')).rejects.toThrow('network down')
    expect(get).toHaveBeenCalledTimes(1)
  })
})

describe('direct lookups', () => {
  beforeEach(() => get.mockReset())

  it('getUnitLoadByLabel hits the by-label endpoint with the raw wire shape', async () => {
    const wire = { id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] }
    get.mockResolvedValueOnce(wire)
    const ul = await menuApi.getUnitLoadByLabel('UL-1')
    expect(get).toHaveBeenCalledWith('/api/v1/unit-loads/by-label/UL-1')
    expect(ul).toEqual(wire)
  })

  it('getLocationByCode hits the by-scan-code endpoint with the raw wire shape', async () => {
    const wire = { id: 3, name: 'A-01-01', lockType: 0, lockTypeName: 'UNLOCKED' }
    get.mockResolvedValueOnce(wire)
    const location = await menuApi.getLocationByCode('A-01-01')
    expect(get).toHaveBeenCalledWith('/api/v1/locations/by-scan-code/A-01-01')
    expect(location).toEqual(wire)
  })

  it('encodes special characters in the scanned code', async () => {
    get.mockResolvedValueOnce({ id: 1, name: 'X', lockType: 0, lockTypeName: 'UNLOCKED' })
    await menuApi.getLocationByCode('A/01 01')
    expect(get).toHaveBeenCalledWith('/api/v1/locations/by-scan-code/A%2F01%2001')
  })

  it('getUnitLoadsByLocation hits the unit-loads list endpoint filtered by locationId', async () => {
    get.mockResolvedValueOnce([{ id: 7, labelId: 'UL-1', storageLocationName: 'A-01-01', unitLoadTypeName: 'EURO', stockUnits: [] }])
    const uls = await menuApi.getUnitLoadsByLocation(3)
    expect(get).toHaveBeenCalledWith('/api/v1/unit-loads?locationId=3')
    expect(uls).toHaveLength(1)
  })
})

describe('adhocMove', () => {
  beforeEach(() => {
    post.mockReset()
    mockKeycloak.tokenParsed = { preferred_username: 'bob' }
  })

  // createManualMove mints the order at RELEASED(100); TaskService.start requires
  // RESERVED(400), so assign (RELEASED->RESERVED, needs an operatorId) MUST run between
  // create and start or start always fails ("Invalid state transition ... 100 -> 500" --
  // caught live by the floor-menu E2E spec, Task 9). destinationLocationName is likewise
  // required at create time: TaskService.complete falls back to the destination location's
  // raw numeric id when no name was ever supplied (same E2E run caught this too).
  it('creates a MOVE transport order then assigns, starts, and completes it', async () => {
    post.mockResolvedValueOnce({ id: 55 })
    post.mockResolvedValueOnce(undefined)
    post.mockResolvedValueOnce(undefined)
    post.mockResolvedValueOnce(undefined)
    await menuApi.adhocMove(7, 31, 'B-02-03')
    expect(post).toHaveBeenNthCalledWith(1, '/api/v1/transport-orders', { unitLoadId: 7, destinationLocationId: 31, destinationLocationName: 'B-02-03' })
    expect(post).toHaveBeenNthCalledWith(2, '/api/v1/transport-orders/55/assign', { operatorId: 'bob' })
    expect(post).toHaveBeenNthCalledWith(3, '/api/v1/transport-orders/55/start')
    expect(post).toHaveBeenNthCalledWith(4, '/api/v1/transport-orders/55/complete')
  })

  it('falls back to a placeholder operatorId when the token has no preferred_username', async () => {
    mockKeycloak.tokenParsed = undefined
    post.mockResolvedValueOnce({ id: 55 })
    post.mockResolvedValueOnce(undefined)
    post.mockResolvedValueOnce(undefined)
    post.mockResolvedValueOnce(undefined)
    await menuApi.adhocMove(7, 31, 'B-02-03')
    expect(post).toHaveBeenNthCalledWith(2, '/api/v1/transport-orders/55/assign', { operatorId: 'floor-operator' })
  })

  it('propagates a failure from the create step without assigning, starting, or completing', async () => {
    const { ApiError } = await import('@/lib/api-client')
    post.mockRejectedValueOnce(new ApiError(notFoundProblem))
    await expect(menuApi.adhocMove(7, 31, 'B-02-03')).rejects.toThrow()
    expect(post).toHaveBeenCalledTimes(1)
  })
})

describe('sort station', () => {
  beforeEach(() => { get.mockReset(); post.mockReset() })

  it('resolveSortCart hits the sort-carts endpoint by unit load id', async () => {
    const cart = { waveId: 9, waveNumber: 'W-9', pickOrderId: 3, pickOrderNumber: 'WB-9-1', unitLoadId: 12, groups: [], items: [] }
    get.mockResolvedValueOnce(cart)
    const result = await menuApi.resolveSortCart(12)
    expect(get).toHaveBeenCalledWith('/api/v1/waves/sort/carts/12')
    expect(result).toEqual(cart)
  })

  it('sortScan posts the scan body to the wave sort-scans endpoint', async () => {
    const scan = { scanId: 77, groupId: 1, sortSlot: '01', destinationKey: 'A', groupState: 'IN_PROGRESS', amount: 1, cartRemainingForItem: 34 }
    post.mockResolvedValueOnce(scan)
    const body = { cartUnitLoadId: 12, itemDataNumber: 'SKU-1', lotNumber: undefined, amount: 1 }
    const result = await menuApi.sortScan(9, body)
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/sort/scans', body)
    expect(result).toEqual(scan)
  })

  it('sortUndo posts to the scan undo endpoint', async () => {
    const scan = { scanId: 77, groupId: 1, sortSlot: '01', destinationKey: 'A', groupState: 'IN_PROGRESS', amount: 1, cartRemainingForItem: 35 }
    post.mockResolvedValueOnce(scan)
    const result = await menuApi.sortUndo(9, 77)
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/sort/scans/77/undo', {})
    expect(result).toEqual(scan)
  })
})

describe('pack-out', () => {
  beforeEach(() => { get.mockReset(); post.mockReset() })

  const packout = {
    shipmentId: 40, shipmentNumber: 'SH-40', shipmentState: 100, groupId: 1, sortSlot: '01',
    destinationKey: 'ACME|Main St|1|10001|NYC|US', memberOrderIds: [11], items: [], containers: [], complete: false,
  }

  it('listReadyGroups fans the CONSOLIDATING waves page out to details and keeps only READY groups', async () => {
    get.mockResolvedValueOnce({ content: [{ id: 9, waveNumber: 'W-9' }, { id: 10, waveNumber: 'W-10' }] })
    get.mockResolvedValueOnce({
      wave: { id: 9, waveNumber: 'W-9' },
      groups: [
        { id: 1, state: 'READY', sortSlot: '01', destinationKey: 'ACME|||10001|NYC|US' },
        { id: 2, state: 'IN_PROGRESS', sortSlot: '02', destinationKey: 'GLOBEX|||20002|DC|US' },
      ],
    })
    get.mockResolvedValueOnce({
      wave: { id: 10, waveNumber: 'W-10' },
      groups: [{ id: 3, state: 'READY', sortSlot: '01', destinationKey: 'INITECH|||30003|LA|US' }],
    })
    const groups = await menuApi.listReadyGroups()
    expect(get).toHaveBeenNthCalledWith(1, '/api/v1/waves?state=CONSOLIDATING&size=50')
    expect(get).toHaveBeenNthCalledWith(2, '/api/v1/waves/9')
    expect(get).toHaveBeenNthCalledWith(3, '/api/v1/waves/10')
    expect(groups).toEqual([
      { waveId: 9, waveNumber: 'W-9', groupId: 1, sortSlot: '01', destinationKey: 'ACME|||10001|NYC|US' },
      { waveId: 10, waveNumber: 'W-10', groupId: 3, sortSlot: '01', destinationKey: 'INITECH|||30003|LA|US' },
    ])
  })

  it('openPackout posts to the group pack-out root', async () => {
    post.mockResolvedValueOnce(packout)
    expect(await menuApi.openPackout(9, 1)).toEqual(packout)
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/consolidation-groups/1/packout')
  })

  it('packoutContainer posts the container body', async () => {
    post.mockResolvedValueOnce(packout)
    await menuApi.packoutContainer(9, 1, { unitLoadId: 601, type: 'PALLET' })
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/consolidation-groups/1/packout/containers', { unitLoadId: 601, type: 'PALLET' })
  })

  it('packoutAddLine posts the line body to the container', async () => {
    post.mockResolvedValueOnce(packout)
    const body = { itemDataNumber: 'SKU-1', lotNumber: undefined, amount: 25 }
    await menuApi.packoutAddLine(9, 1, 77, body)
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/consolidation-groups/1/packout/containers/77/lines', body)
  })

  it('packoutClose posts the weight to the container close route', async () => {
    post.mockResolvedValueOnce(packout)
    await menuApi.packoutClose(9, 1, 77, 2.5)
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/consolidation-groups/1/packout/containers/77/close', { weight: 2.5 })
  })

  it('packoutComplete posts to the complete route', async () => {
    post.mockResolvedValueOnce({ ...packout, complete: true })
    const done = await menuApi.packoutComplete(9, 1)
    expect(post).toHaveBeenCalledWith('/api/v1/waves/9/consolidation-groups/1/packout/complete')
    expect(done.complete).toBe(true)
  })
})

import { api, ApiError } from '@/lib/api-client'
import { keycloak } from '@/lib/keycloak'

// Wire shapes confirmed against the backend DTOs (2026-08-19, Task 3 caution block):
//  - UnitLoadResponse: services/inventory-service/karyo-inventory-api/.../dto/UnitLoadDto.kt
//    -> id, labelId, storageLocationName (NOT locationName), unitLoadTypeName, stockUnits: StockUnitSummary[]
//  - StockUnitSummary (same file): id, itemDataNumber, amount, lotNumber?, state
//  - LocationResponse: services/warehouse-layout-service/karyo-layout-api/.../dto/LocationResponse.kt
//    -> id, name, lockType (Int), lockTypeName (String) -- there is NO `locked: boolean` field on
//    the wire; UNLOCKED is code 0 (see layout's LockType enum), so the screen derives "locked" from
//    lockType !== 0 rather than the plan's guessed `locked` boolean.
export interface UnitLoadStockLine { id: number; itemDataNumber: string; amount: number; lotNumber?: string; state: number }
export interface UnitLoadInfo {
  id: number
  labelId: string
  storageLocationName: string
  unitLoadTypeName: string
  stockUnits: UnitLoadStockLine[]
}
export interface LocationInfo { id: number; name: string; lockType: number; lockTypeName: string }
export type ScanHit =
  | { kind: 'unitLoad'; ul: UnitLoadInfo }
  | { kind: 'location'; location: LocationInfo }
  | { kind: 'none'; code: string }

const notFound = (e: unknown) => e instanceof ApiError // any API failure falls through to the next resolver

/** ASNs share the 18-value OrderState code space (see order-service's OrderState) --
 *  there is no dedicated AsnState enum. Per the Asn entity KDoc, an ASN's lifecycle is
 *  CREATED(50) -> RELEASED(100) -> STARTED(500) -> FINISHED(700); CANCELED(800) is
 *  allowed pre-STARTED only. FINISHED and CANCELED (and anything numerically at or
 *  past FINISHED) are terminal, so "open" is state < 700. */
const CLOSED_ASN_STATE = 700

export interface OpenAsn { id: number; asnNumber: string; supplierName?: string; state: number }

export interface SortGroup { groupId: number; sortSlot: string; destinationKey: string; state: string; picked: number; sorted: number; remaining: number }
export interface SortCartItem { itemDataId: number; itemDataNumber: string; lotNumber: string | null; picked: number; sorted: number; remaining: number }
export interface SortCart { waveId: number; waveNumber: string; pickOrderId: number; pickOrderNumber: string; unitLoadId: number; groups: SortGroup[]; items: SortCartItem[] }
export interface SortScan { scanId: number; groupId: number; sortSlot: string; destinationKey: string; groupState: string; amount: number; cartRemainingForItem: number }

/** Pack-out wire shapes (Bulk Allocation Sprint C), mirroring PackoutDtos.kt in
 *  karyo-wave-api. Everything on `Packout` is derived server-side on every read, so the screen
 *  never reconciles a counter itself -- it just re-renders whatever the last call returned. */
export interface PackoutContainerLine { deliveryOrderId: number; itemDataNumber: string; lotNumber: string | null; amount: number }
export interface PackoutContainer {
  id: number
  shippingUnitNumber: string
  unitLoadId: number
  state: 'OPEN' | 'CLOSED'
  type: string
  weight: number
  lines: PackoutContainerLine[]
}
export interface PackoutItem { itemDataId: number; itemDataNumber: string; lotNumber: string | null; sorted: number; packed: number; remaining: number }
export interface Packout {
  shipmentId: number
  shipmentNumber: string
  shipmentState: number
  groupId: number
  sortSlot: string
  destinationKey: string
  memberOrderIds: number[]
  items: PackoutItem[]
  containers: PackoutContainer[]
  complete: boolean
}
export interface ReadyGroup { waveId: number; waveNumber: string; groupId: number; sortSlot: string; destinationKey: string }

const packoutRoot = (waveId: number, groupId: number) => `/api/v1/waves/${waveId}/consolidation-groups/${groupId}/packout`

export const menuApi = {
  getUnitLoadByLabel: (label: string) => api.get<UnitLoadInfo>(`/api/v1/unit-loads/by-label/${encodeURIComponent(label)}`),
  getLocationByCode: (code: string) => api.get<LocationInfo>(`/api/v1/locations/by-scan-code/${encodeURIComponent(code)}`),
  getUnitLoadsByLocation: (locationId: number) => api.get<UnitLoadInfo[]>(`/api/v1/unit-loads?locationId=${locationId}`),

  /** Open (non-terminal) ASNs, for the receive-select screen's pick list. */
  listOpenAsns: async (): Promise<OpenAsn[]> => {
    const page = await api.get<{ content: OpenAsn[] }>('/api/v1/asns?size=50')
    return page.content.filter((a) => a.state < CLOSED_ASN_STATE)
  },

  /** Find the goods-receipt already bound to this ASN, or create one bound to it. */
  receiptForAsn: async (asnId: number): Promise<number> => {
    const page = await api.get<{ content: { id: number }[] }>(`/api/v1/goods-receipts?asnId=${asnId}`)
    if (page.content.length > 0) return page.content[0].id
    const created = await api.post<{ id: number }>('/api/v1/goods-receipts', { asnIds: [asnId] })
    return created.id
  },

  /** Resolve a free scan in priority order: unit-load label, then location code. */
  async resolveScan(code: string): Promise<ScanHit> {
    try { return { kind: 'unitLoad', ul: await this.getUnitLoadByLabel(code) } } catch (e) { if (!notFound(e)) throw e }
    try { return { kind: 'location', location: await this.getLocationByCode(code) } } catch (e) { if (!notFound(e)) throw e }
    return { kind: 'none', code }
  },

  /** Ad-hoc move: create a manual MOVE transport order, then execute it immediately
   *  (assign + start + complete) as the operator physically doing the move themselves.
   *  `createManualMove` mints the order at RELEASED(100) -- `start` requires RESERVED(400)
   *  (TaskService.start: `order.state != OrderState.RESERVED.code` throws InvalidTransition),
   *  so `assign` (RELEASED->RESERVED, needs an operatorId) MUST run first or `start` 404s/400s
   *  every single call. Caught by the floor-menu E2E spec (Task 9): the original 3-call
   *  create+start+complete sequence failed 100% of the time with "Invalid state transition for
   *  transport order N: 100 -> 500" (TaskService.start's own InvalidTransition report, which
   *  always cites its target STARTED.code=500 regardless of the actual current state).
   *
   *  `destinationLocationName` is REQUIRED too, not cosmetic: TaskService.complete's `destName`
   *  falls back through `request.destinationLocationName -> order.suggestedLocationName ->
   *  destId.toString()` when nothing was supplied at create time -- so an omitted name doesn't
   *  just leave a blank label, it silently stamps the moved unit load's storageLocationName with
   *  the destination location's raw numeric id (e.g. "33") instead of its real name. Also caught
   *  by the E2E spec's re-run-Inquiry assertion, same run as the assign gap above. */
  async adhocMove(unitLoadId: number, destinationLocationId: number, destinationLocationName: string): Promise<void> {
    const order = await api.post<{ id: number }>('/api/v1/transport-orders', { unitLoadId, destinationLocationId, destinationLocationName })
    const operatorId = (keycloak.tokenParsed?.preferred_username as string | undefined) ?? 'floor-operator'
    await api.post(`/api/v1/transport-orders/${order.id}/assign`, { operatorId })
    await api.post(`/api/v1/transport-orders/${order.id}/start`)
    await api.post(`/api/v1/transport-orders/${order.id}/complete`)
  },

  /** Ad-hoc blind cycle count of one scanned location: create-session already returns the
   *  generated orders, so this returns the count-order id directly -- no separate claim step,
   *  count-execution.tsx reads the order by id with no `/work/{ref}` claim call of its own. */
  async startAdhocCount(locationId: number): Promise<number> {
    const session = await api.post<{ orders: { id: number }[] }>('/api/v1/count-sessions', { locationIds: [locationId] })
    return session.orders[0].id
  },

  /** The PICKED (600) pick order whose target container is this UL; throws when none.
   *  NOTE (Task 7, re-confirmed 2026-08-19): `GET /api/v1/pick-orders` returns a plain
   *  `PickOrderResponse[]`, NOT the `{content: [...]}` paginated wrapper used by /asns and
   *  /goods-receipts (see PickOrderResource.list in fulfillment-core -- `fun list():
   *  List<PickOrderResponse>`). The task-7 brief assumed pagination here; it's wrong. */
  findPickOrderByTargetUl: async (unitLoadId: number) => {
    const orders = await api.get<{ id: number; pickOrderNumber: string; deliveryOrderId: number | null; state: number; targetUnitLoadId: number | null }[]>(
      '/api/v1/pick-orders',
    )
    const hit = orders.find((p) => p.state === 600 && p.targetUnitLoadId === unitLoadId)
    if (!hit) throw new Error('No picked order for this unit load')
    return hit
  },

  /** Open (or find the already-open) shipment for a delivery order. A 409 from `POST
   *  /shipments` means one already exists (S4: unless CANCELED, which the create path already
   *  frees) -- fall back to the flat `GET /shipments` list (ShipmentResponse.deliveryOrderId,
   *  confirmed against ShipmentDtos.kt) and match it there. */
  openShipment: async (deliveryOrderId: number): Promise<{ id: number }> => {
    try {
      return await api.post<{ id: number }>('/api/v1/shipments', { deliveryOrderId })
    } catch (e) {
      if (!(e instanceof ApiError && e.problem.status === 409)) throw e
      const all = await api.get<{ id: number; deliveryOrderId: number }[]>('/api/v1/shipments')
      const open = all.find((s) => s.deliveryOrderId === deliveryOrderId)
      if (!open) throw e
      return { id: open.id }
    }
  },

  packShipment: (shipmentId: number, weight: number, type: string) =>
    api.post<unknown>(`/api/v1/shipments/${shipmentId}/pack`, { weight, type }).then(() => undefined),

  /** Re-send the ZPL label for an already-created unit load to whatever printer is configured
   *  for this tenant (Task 1). 204 on success; 503 "No printer configured" / 502 "Printer
   *  unreachable" surface as ApiError with an RFC 7807 problem.detail for the screen to toast. */
  printLabel: (unitLoadId: number) =>
    api.post<undefined>(`/api/v1/unit-loads/${unitLoadId}/label/print`),

  /** Sort station (Bulk Allocation Sprint A). The cart is a PICKED batch pick container; the
   *  route takes the unit load ID, resolved from the scanned label via getUnitLoadByLabel. */
  resolveSortCart: (unitLoadId: number) => api.get<SortCart>(`/api/v1/waves/sort/carts/${unitLoadId}`),
  sortScan: (waveId: number, body: { cartUnitLoadId: number; itemDataNumber: string; lotNumber?: string; amount: number }) =>
    api.post<SortScan>(`/api/v1/waves/${waveId}/sort/scans`, body),
  sortUndo: (waveId: number, scanId: number) =>
    api.post<SortScan>(`/api/v1/waves/${waveId}/sort/scans/${scanId}/undo`, {}),

  /** Every READY consolidation group the packer could work on right now.
   *
   *  v1 SIMPLIFICATION: this is assembled client-side -- one page of CONSOLIDATING waves
   *  (`?size=50`) plus one detail fetch per wave, flattening the groups whose state is READY.
   *  There is no server-side "ready groups" index, and a wave only sits in CONSOLIDATING while
   *  its picks drain, so the pool is small and the fan-out is bounded at 50 detail calls. If a
   *  site ever runs more than 50 concurrent CONSOLIDATING waves the tail is silently dropped
   *  (the page is not walked) -- that is the point at which this belongs in wave-core as a real
   *  endpoint rather than here. */
  listReadyGroups: async (): Promise<ReadyGroup[]> => {
    const page = await api.get<{ content: { id: number; waveNumber: string }[] }>('/api/v1/waves?state=CONSOLIDATING&size=50')
    const details = await Promise.all(
      page.content.map((w) =>
        api.get<{
          wave: { id: number; waveNumber: string }
          groups: { id: number; state: string; sortSlot: string; destinationKey: string }[]
        }>(`/api/v1/waves/${w.id}`),
      ),
    )
    return details.flatMap((d) =>
      d.groups
        .filter((g) => g.state === 'READY')
        .map((g) => ({ waveId: d.wave.id, waveNumber: d.wave.waveNumber, groupId: g.id, sortSlot: g.sortSlot, destinationKey: g.destinationKey })),
    )
  },

  /** Idempotent on the server: a second call returns the group's existing shipment. 201. */
  openPackout: (waveId: number, groupId: number) => api.post<Packout>(packoutRoot(waveId, groupId)),
  packoutContainer: (waveId: number, groupId: number, body: { unitLoadId?: number; type: string }) =>
    api.post<Packout>(`${packoutRoot(waveId, groupId)}/containers`, body),
  packoutAddLine: (waveId: number, groupId: number, containerId: number, body: { itemDataNumber: string; lotNumber?: string; amount: number }) =>
    api.post<Packout>(`${packoutRoot(waveId, groupId)}/containers/${containerId}/lines`, body),
  packoutClose: (waveId: number, groupId: number, containerId: number, weight: number) =>
    api.post<Packout>(`${packoutRoot(waveId, groupId)}/containers/${containerId}/close`, { weight }),
  /** 409 `wave-packout-conflict` while anything is unpacked or a container is still open. */
  packoutComplete: (waveId: number, groupId: number) => api.post<Packout>(`${packoutRoot(waveId, groupId)}/complete`),
}

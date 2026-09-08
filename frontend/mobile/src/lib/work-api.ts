import { api, isOfflineError } from '@/lib/api-client'
import { enqueue, type QueuedOp } from '@/lib/offline/op-queue'
import { notifyChange } from '@/lib/offline/sync'

export interface WorkItem { ref: string; workType: string; priority: number; state: string; claimedBy?: string; primaryLocation?: string; destination?: string; summary: string }
export interface PickLine { id: number; itemDataNumber: string; sourceStockUnitId: number; plannedAmount: number; pickedAmount: number; state: number }
export interface PickOrder { id: number; pickOrderNumber: string; deliveryOrderNumber: string | null; state: number; bulk: boolean; picks: PickLine[] }
export interface BulkLine { sourceStockUnitId: number; locationName: string; unitLoadLabel: string; itemDataNumber: string; lotNumber: string | null; plannedTotal: number; pickedTotal: number; openSlices: number }
export interface BulkConfirmResult { filledSlices: number; shortSlices: number }
export interface StockUnit { id: number; locationName: string; itemDataNumber: string }
export interface TransportOrder {
  id: number; orderNumber: string; transportType: string; unitLoadLabel: string;
  sourceLocationName: string; destinationLocationName: string | null; suggestedLocationName: string | null; state: number;
  /** PT18: orthogonal pause stamp -- non-null means paused; `state` never moves (see backend
   *  `TransportOrderResponse.pausedAt`). */
  pausedAt: string | null;
}
/** `counted` (St4): true once this line was already resolved elsewhere (e.g. the web app's
 *  "unit load missing" action zeroed it) -- the floor app must skip it, not re-prompt for it
 *  (submitCount now 422s a fresh input against a non-PLANNED line). */
export interface CountEntryLine { lineId: number; itemDataNumber: string; lotNumber?: string; serialNumber?: string; counted?: boolean }
export interface CountEntry { id: number; orderNumber: string; locationName: string; lines: CountEntryLine[] }
/** An expected ASN line still open (remainingAmount > 0) on a receipt being received. */
export interface ReceiveExpectedLine { asnLineId: number; asnNumber: string; itemDataNumber: string; remainingAmount: number }
export interface ReceiveEntry {
  id: number; receiptNumber: string; state: number
  dockLocationId: number | null; dockLocationName: string | null; pausedAt: string | null
  lines: ReceiveExpectedLine[]
  /** Lines already received on this receipt (any ASN), regardless of session — context only. */
  receivedCount: number
}
export interface ReceiveLineBody {
  asnLineId: number; amount: number; locationId: number; locationName: string
  unitLoadLabel?: string; allowOverReceipt: boolean
}
/** Minimal wire shapes read from GET /goods-receipts/{id} and GET /asns/{id} -- only the fields
 *  getReceiveEntry projects into ReceiveEntry (full DTOs live in karyo-orders-api). */
interface GoodsReceiptWire {
  id: number; receiptNumber: string; state: number
  dockLocationId: number | null; dockLocationName: string | null; pausedAt: string | null
  asns: { id: number; asnNumber: string }[]
  lines: unknown[]
}
interface AsnLineWire { id: number; itemDataNumber: string; remainingAmount: number }
interface AsnWire { asnNumber: string; lines: AsnLineWire[] }

async function queuedPost<T>(op: { type: QueuedOp['type']; url: string; body?: unknown; label: string; taskRef?: string }): Promise<T | undefined> {
  const enqueueOp = () => enqueue({ id: crypto.randomUUID(), method: 'POST', status: 'pending', createdAt: Date.now(), ...op })
  if (!navigator.onLine) { await enqueueOp(); notifyChange(); return undefined }
  try { return await api.post<T>(op.url, op.body) }
  catch (e) { if (isOfflineError(e)) { await enqueueOp(); notifyChange(); return undefined } throw e }
}

export const workApi = {
  mine: () => api.get<WorkItem[]>('/api/v1/work/mine'),
  next: () => api.post<WorkItem | undefined>('/api/v1/work/next').then((r) => r ?? null),
  release: (ref: string) => queuedPost<void>({ type: 'release', url: `/api/v1/work/${encodeURIComponent(ref)}/release`, label: `Release ${ref}`, taskRef: ref }),
  getPickOrder: (id: number) => api.get<PickOrder>(`/api/v1/pick-orders/${id}`),
  confirmPick: (pickId: number, pickedAmount: number) => queuedPost<PickLine>({ type: 'pick-confirm', url: `/api/v1/picks/${pickId}/confirm`, body: { pickedAmount }, label: `Confirm pick #${pickId}` }),
  getBulkLines: (pickOrderId: number) => api.get<BulkLine[]>(`/api/v1/pick-orders/${pickOrderId}/bulk-lines`),
  /** Online-only by design: the fan-out outcome is shown to the operator at once, and a queued
   *  replay of a short bulk confirm could double-apply. Not a queuedPost. */
  bulkConfirm: (pickOrderId: number, body: { sourceStockUnitId: number; pickedAmount: number }) =>
    api.post<BulkConfirmResult>(`/api/v1/pick-orders/${pickOrderId}/bulk-confirm`, body),
  getStockUnit: (id: number) => api.get<StockUnit>(`/api/v1/stock-units/${id}`),
  getTransportOrder: (id: number) => api.get<TransportOrder>(`/api/v1/transport-orders/${id}`),
  startTransport: (id: number) => queuedPost<TransportOrder>({ type: 'transport-start', url: `/api/v1/transport-orders/${id}/start`, body: {}, label: `Start move #${id}` }),
  completeTransport: (id: number) => queuedPost<TransportOrder>({ type: 'transport-complete', url: `/api/v1/transport-orders/${id}/complete`, body: {}, label: `Complete move #${id}` }),
  getCountEntry: (id: number) => api.get<CountEntry>(`/api/v1/count-orders/${id}?view=entry`),
  submitCount: (id: number, lines: { lineId: number; countedAmount: number }[]) => queuedPost<unknown>({ type: 'count-submit', url: `/api/v1/count-orders/${id}/count`, body: { lines }, label: `Submit count #${id}` }),
  /** Zero-line count order (nothing on record at this location): confirming it empty is the ONLY
   *  terminal path -- `submitCount` with no lines 422s, so the wizard must never offer it here. */
  locationEmpty: (id: number) => queuedPost<unknown>({ type: 'count-location-empty', url: `/api/v1/count-orders/${id}/location-empty`, label: `Location empty #${id}` }),
  /** Receiving is ONLINE-ONLY: receiveLine is not idempotent (a queued replay on reconnect would
   *  double-create stock), so this posts directly through `api` -- never `queuedPost`. */
  getReceiveEntry: async (id: number): Promise<ReceiveEntry> => {
    const receipt = await api.get<GoodsReceiptWire>(`/api/v1/goods-receipts/${id}`)
    const asns = await Promise.all(receipt.asns.map((a) => api.get<AsnWire>(`/api/v1/asns/${a.id}`)))
    const lines: ReceiveExpectedLine[] = asns.flatMap((asn) =>
      asn.lines.filter((l) => l.remainingAmount > 0).map((l) => ({
        asnLineId: l.id, asnNumber: asn.asnNumber, itemDataNumber: l.itemDataNumber, remainingAmount: l.remainingAmount,
      })),
    )
    return {
      id: receipt.id, receiptNumber: receipt.receiptNumber, state: receipt.state,
      dockLocationId: receipt.dockLocationId, dockLocationName: receipt.dockLocationName, pausedAt: receipt.pausedAt,
      lines, receivedCount: receipt.lines.length,
    }
  },
  receiveLine: (receiptId: number, body: ReceiveLineBody) => api.post<unknown>(`/api/v1/goods-receipts/${receiptId}/lines`, body),
}

/** Parse the numeric source id from a WorkRef token like "PICK:42". */
export function refId(ref: string): number { return Number(ref.split(':')[1]) }

/** Maps a WorkItem.workType to its execution route. An unrecognized type falls back to the
 *  inbox ('/'), not a screen — routing a work item we don't understand into e.g. the move
 *  screen would misrepresent what the operator is being asked to do.
 *  Cross-docking sprint (Task 3 fix-round): CROSS_DOCK now joins the work-inbox
 *  (`TransportWorkProvider.workTypes()`), so it rides the same 'move' execution screen as
 *  every other transport type -- `move-execution.tsx` renders `order.transportType` as a plain
 *  label, it does not switch on it, so no new screen is needed. */
export function routeFor(workType: string, ref: string): string {
  const seg =
    workType === 'PICK' ? 'pick'
    : workType === 'COUNT' ? 'count'
    : workType === 'RECEIVE' ? 'receive'
    : workType === 'PUTAWAY' || workType === 'MOVE' || workType === 'REPLENISH' || workType === 'TRANSFER' || workType === 'CROSS_DOCK' ? 'move'
    : undefined
  return seg ? `/${seg}/${encodeURIComponent(ref)}` : '/'
}

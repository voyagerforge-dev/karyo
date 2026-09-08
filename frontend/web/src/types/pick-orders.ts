export interface PickResponse {
  id: number;
  /** Null for an EXTINGUISH pick (V605, WORKLIST row 20) — no backing DeliveryOrder line. */
  deliveryOrderLineId: number | null;
  itemDataId: number;
  itemDataNumber: string;
  sourceStockUnitId: number;
  plannedAmount: number;
  pickedAmount: number;
  state: number;
  lotNumber: string | null;
  pickingType: string; // "COMPLETE" | "PICK" | "EXTINGUISH"
  followUpForPickId: number | null;
  substitutedItemDataId: number | null;
  /** Actuals captured from the source stock at confirm time (row 18) — null pre-confirm. */
  pickedLotNumber: string | null;
  /** ISO date (LocalDate) — null pre-confirm or when the source stock carried none. */
  pickedBestBefore: string | null;
}

export interface PickOrderResponse {
  id: number;
  pickOrderNumber: string;
  /** Null for an EXTINGUISH order (V605, WORKLIST row 20) — no backing DeliveryOrder. */
  deliveryOrderId: number | null;
  /** Null in lockstep with deliveryOrderId; render "—" or fall back to pickOrderNumber. */
  deliveryOrderNumber: string | null;
  state: number;
  targetUnitLoadId: number | null;
  picks: PickResponse[];
  /** Row 19: computed on read, myWMS two-aggregate rule — null when the sum is zero. */
  weight: number | null;
  volume: number | null;
  /** Row 8: resolved once at release time (order's own destination, else the strategy default). */
  destinationLocationId: number | null;
  /** Wave Sprint B: true for a batch pick order (deliveryOrderId null, waveId set, wavePickMode
   * BULK) -- desktop renders the bulk-lines pane instead of the per-pick table. */
  bulk: boolean;
  /**
   * Row :1470 (A8): derived, non-persisted signal -- true iff the order's strategy has
   * createShippingOrder on, this pick order is PICKED, and no non-canceled shipment exists yet
   * for the delivery order. Computed on the DETAIL response only (GET /pick-orders/{id}); the
   * list endpoint always reports false here (see the backend's own KDoc) -- a self-healing
   * signal, not a stored flag: it clears the moment packing is opened, manually or otherwise.
   */
  autoOpenPending: boolean;
}

export interface ReleaseToPickingRequest {
  deliveryOrderId: number;
  /** Overrides `karyo.fulfillment.pick-bin-unit-load-type-id` (row 17). */
  targetUnitLoadTypeId?: number;
}

export interface ConfirmPickRequest {
  pickedAmount: number;
  targetUnitLoadId?: number;
}

/**
 * Row 20 — exactly one of stockUnitIds/unitLoadId is required (enforced
 * backend-side, 400 otherwise).
 */
export interface ExtinguishRequest {
  stockUnitIds?: number[];
  unitLoadId?: number;
  targetUnitLoadTypeId?: number;
}

/**
 * Wave Sprint B: one aggregated source-stock row for a BULK pick order's
 * bulk-lines pane -- GET /api/v1/pick-orders/{id}/bulk-lines.
 */
export interface BulkLineResponse {
  sourceStockUnitId: number;
  locationName: string;
  unitLoadLabel: string;
  itemDataId: number;
  itemDataNumber: string;
  lotNumber: string | null;
  plannedTotal: number;
  pickedTotal: number;
  openSlices: number;
}

/**
 * Wave Sprint B: fan-out confirm result for one bulk source stock unit --
 * POST /api/v1/pick-orders/{id}/bulk-confirm.
 */
export interface BulkConfirmResponse {
  pickOrderId: number;
  sourceStockUnitId: number;
  pickedAmount: number;
  filledSlices: number;
  shortSlices: number;
  slices: Array<{ pickId: number; deliveryOrderLineId: number | null; picked: number; planned: number }>;
}

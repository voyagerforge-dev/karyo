/**
 * TypeScript interfaces matching karyo-orders ASN + GoodsReceipt DTOs.
 * @see services/order-service/karyo-orders-api/src/main/kotlin/com/karyo/orders/dto/AsnDtos.kt
 * @see services/order-service/karyo-orders-api/src/main/kotlin/com/karyo/orders/dto/GoodsReceiptDtos.kt
 */

// ── ASN ──────────────────────────────────────────────────────────────────

export interface AsnLineResponse {
  id: number;
  lineNumber: number;
  itemDataId: number;
  itemDataNumber: string;
  expectedAmount: number;
  receivedAmount: number;
  /** expected - received (0 when fully received) */
  remainingAmount: number;
  /** received/expected as a floored integer percent (may exceed 100 on over-receipt) */
  progressPercent: number;
  state: number;
  stateName: string;
  lotNumber: string | null;
}

export interface AsnResponse {
  id: number;
  asnNumber: string;
  externalNumber: string | null;
  carrierName: string | null;
  supplierName: string | null;
  senderName: string | null;
  /** ISO date (yyyy-MM-dd) */
  expectedDate: string | null;
  notes: string | null;
  state: number;
  stateName: string;
  clientId: number;
  /** totalReceived/totalExpected (floored; may exceed 100) */
  progressPercent: number;
  lines: AsnLineResponse[];
  created: string;
  modified: string;
  /** UL pre-advices registered on this ASN. */
  ulAdvices: UlAdviceResponse[];
}

export interface CreateAsnLineRequest {
  itemDataId: number;
  expectedAmount: number;
  lotNumber?: string;
}

export interface CreateAsnRequest {
  /** Generated (ASN-prefixed) when omitted */
  asnNumber?: string;
  externalNumber?: string;
  carrierName?: string;
  supplierName?: string;
  senderName?: string;
  /** ISO date (yyyy-MM-dd) */
  expectedDate?: string;
  notes?: string;
  lines: CreateAsnLineRequest[];
}

/** Pre-release header edits only — backend rejects with 409 unless state is CREATED. */
export interface UpdateAsnRequest {
  externalNumber?: string;
  carrierName?: string;
  supplierName?: string;
  senderName?: string;
  expectedDate?: string;
  notes?: string;
}

export interface AsnLineShortage {
  lineId: number;
  lineNumber: number;
  itemDataId: number;
  itemDataNumber: string;
  expectedAmount: number;
  receivedAmount: number;
  shortfall: number;
}

/** Result of force-finishing an ASN: updated ASN + the short lines. */
export interface AsnFinishResponse {
  asn: AsnResponse;
  shortages: AsnLineShortage[];
}

/**
 * Registers a UL pre-advice on an ASN. `labelId` is caller-supplied or
 * server-generated (`ULA-` prefixed) when omitted; unique per ASN.
 */
export interface CreateUlAdviceRequest {
  labelId?: string;
  unitLoadTypeId?: number;
  itemDataId?: number;
  expectedAmount?: number;
  reasonForReturn?: string;
}

/**
 * A registered UL pre-advice. `state`/`stateName` follow the CREATED ->
 * FINISHED (match) / CANCELED (delete) subset; `matchedReceiptLineId` is set
 * only once FINISHED.
 */
export interface UlAdviceResponse {
  id: number;
  labelId: string;
  unitLoadTypeId: number | null;
  itemDataId: number | null;
  itemDataNumber: string | null;
  expectedAmount: number | null;
  reasonForReturn: string | null;
  state: number;
  stateName: string;
  matchedReceiptLineId: number | null;
}

// ── Goods receipt ──────────────────────────────────────────────────────────

export interface GoodsReceiptLineResponse {
  id: number;
  asnLineId: number | null;
  itemDataId: number;
  itemDataNumber: string;
  amount: number;
  locationId: number;
  locationName: string;
  unitLoadLabel: string;
  stockUnitId: number;
  unitLoadId: number;
  lotNumber: string | null;
  bestBefore: string | null;
  serialNumber: string | null;
  packagingUnitId: number | null;
  /** Inventory LockType code applied at receipt; null = received unlocked. */
  lockType: number | null;
  /** Full operator note as recorded on the line. */
  note: string | null;
  /** DERIVED: any lock was applied at receipt (not only a QA fault). */
  qaHold: boolean;
  /** B3: true once the line has been totally reversed (stock deleted, ASN decremented). */
  reversed: boolean;
  /** B3: when the line was reversed; null = never reversed. */
  reversedAt: string | null;
  /** Inbound-completion row 7: the validated per-line putaway strategy override, if any. */
  storageStrategyId: number | null;
}

/** A bound ASN's id + number — the `GoodsReceiptResponse.asns` element shape (V424 M2M). */
export interface AsnRefResponse {
  id: number;
  asnNumber: string;
}

export interface GoodsReceiptResponse {
  id: number;
  receiptNumber: string;
  /** V424 many-to-many: every ASN currently bound to this receipt (empty = blind). */
  asns: AsnRefResponse[];
  carrierName: string | null;
  deliveryNoteNumber: string | null;
  notes: string | null;
  receiptType: number;
  prio: number;
  receiptDate: string | null;
  dockLocationId: number | null;
  dockLocationName: string | null;
  operatorId: string | null;
  pausedAt: string | null;
  state: number;
  stateName: string;
  clientId: number;
  lines: GoodsReceiptLineResponse[];
  created: string;
  modified: string;
}

export interface CreateGoodsReceiptRequest {
  /** Generated (GR-prefixed) when omitted */
  receiptNumber?: string;
  /** Binds any number of RELEASED/STARTED ASNs of the same client; empty/omitted = blind receipt. */
  asnIds?: number[];
  carrierName?: string;
  deliveryNoteNumber?: string;
  notes?: string;
  receiptType?: number;
  prio?: number;
  receiptDate?: string;
  dockLocationId?: number;
  dockLocationName?: string;
}

export interface ReceiveLineRequest {
  /** Line of one of the receipt's linked ASNs (itemDataId defaults from it) … */
  asnLineId?: number;
  /** … or a blind line product. One of asnLineId/itemDataId is required. */
  itemDataId?: number;
  amount: number;
  locationId: number;
  locationName: string;
  unitLoadLabel?: string;
  unitLoadTypeId?: number;
  lotNumber?: string;
  /** ISO date (yyyy-MM-dd) */
  bestBefore?: string;
  serialNumber?: string;
  packagingUnitId?: number;
  /**
   * Inventory LockType code {1, 103, 202, 203} — keeps the stock INCOMING + locked
   * until manually released. Omit for no lock (explicit 0 is rejected with 422).
   */
  lockType?: number;
  /** Free-text lock/line note (max 255; journal copy truncated visibly to 50). */
  note?: string;
  /**
   * Overrides the over-receipt guard (received + amount may then exceed the ASN
   * line's expectedAmount). The instance-level over-receipt knob can still hard-stop
   * this even when true — see the receive form's over-receipt confirm flow.
   */
  allowOverReceipt: boolean;
  /**
   * Optional per-line override of the putaway location finder's StorageStrategy —
   * an ID-only reference into the layout module. `undefined` leaves the finder's
   * normal resolution untouched. No UI control yet (type mirrors the wire).
   */
  storageStrategyId?: number;
}

export interface ReceiveLineResponse {
  receipt: GoodsReceiptResponse;
  lineId: number;
  stockUnitId: number;
  unitLoadId: number;
  unitLoadLabel: string;
}

/** PUT /api/v1/goods-receipts/{id} — prio/receiptDate/dock only (B7). */
export interface UpdateGoodsReceiptRequest {
  prio?: number;
  receiptDate?: string;
  dockLocationId?: number;
  dockLocationName?: string;
}

export const GOODS_RECEIPT_TYPE = { NORMAL: 0, RETOUR: 1 } as const;

// ── State tables ────────────────────────────────────────────────────────────

/**
 * ASN/receipt lifecycle states wired in v1.2 (shared OrderState codes). The
 * list filter only offers states an entity can actually be in today.
 */
export const ASN_STATES = [
  { code: 50, name: 'Created' },
  { code: 100, name: 'Released' },
  { code: 500, name: 'Started' },
  { code: 700, name: 'Finished' },
  { code: 800, name: 'Canceled' },
] as const;

export const GOODS_RECEIPT_STATES = [
  { code: 50, name: 'Created' },
  { code: 500, name: 'Started' },
  { code: 700, name: 'Finished' },
  { code: 800, name: 'Canceled' },
] as const;

/** OrderState codes used by the receiving UI logic. */
export const RECEIVING_STATE = {
  CREATED: 50,
  RELEASED: 100,
  STARTED: 500,
  FINISHED: 700,
  CANCELED: 800,
} as const;

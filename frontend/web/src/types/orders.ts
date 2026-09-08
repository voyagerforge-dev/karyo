/**
 * TypeScript interfaces matching karyo-orders DTOs.
 * @see services/order-service/karyo-orders-api/src/main/kotlin/com/karyo/orders/dto/
 */

export interface DeliveryOrderLineResponse {
  id: number;
  lineNumber: number;
  itemDataId: number;
  itemDataNumber: string;
  /** Product name via ProductLookup; null when the product is missing/foreign-tenant (honest gap — fall back to itemDataNumber). */
  itemDataName: string | null;
  amount: number;
  reservedAmount: number;
  /** amount - reservedAmount; > 0 means the line sits in PENDING(550) after release */
  shortage: number;
  state: number;
  stateName: string;
  lotNumber: string | null;
  /** Per-line unit price at order time; null = honest gap (un-priced line). */
  unitPrice: number | null;
  externalNumber: string | null;
  pickedAmount: number;
  substitutedAmount: number;
}

export interface DeliveryOrderResponse {
  id: number;
  orderNumber: string;
  externalNumber: string | null;
  customerName: string | null;
  street: string | null;
  streetNumber: string | null;
  zipCode: string | null;
  city: string | null;
  country: string | null;
  phone: string | null;
  email: string | null;
  /** ISO date (yyyy-MM-dd) */
  deliveryDate: string | null;
  prio: number;
  notes: string | null;
  pickingHint: string | null;
  packingHint: string | null;
  shippingHint: string | null;
  state: number;
  stateName: string;
  orderStrategyId: number | null;
  clientId: number;
  lines: DeliveryOrderLineResponse[];
  created: string;
  modified: string;
  /** Via ShipmentLookup SPI; null until a shipment exists for this order (honest gap — keep "—"). */
  carrierName: string | null;
  carrierService: string | null;
  trackingNumber: string | null;
  shippedAt: string | null;
  /** Row 10: which StorageLocation inside this warehouse the order's work is bound for (NOT the ship-to address above). */
  destinationLocationId: number | null;
  /** Resolved via StorageLocationLookup; null when destinationLocationId is null or no longer resolves. */
  destinationLocationName: string | null;
  /** Row 10: claiming operator (pure metadata, never coupled to state). Null = unclaimed. */
  operatorId: string | null;
  /** Row 10: the party named as sender on this order's outbound paperwork (Karyo-native). */
  senderName: string | null;
  /** Row 10: derived, computed-on-read -- always this order's delivery note route. */
  documentUrl: string;
  /** Row 10: derived, computed-on-read -- the shipping-unit label route once a shipment (with a shipping unit) exists, else null. */
  labelUrl: string | null;
  /** Order streaming (B3): API-only per-order override of the strategy's releaseMode; null = use the strategy's key. */
  releaseModeOverride?: string | null;
  /** Order streaming (B3): stamped on the streaming engine's first release attempt for this order. */
  streamFirstAttemptAt?: string | null;
  /** Order streaming (B3): stamped once tier-2 escalation fires (still retried). */
  streamEscalatedAt?: string | null;
  /** Order streaming (B3): stamped once tier-3 stall fires (excluded from auto-retry). */
  streamStalledAt?: string | null;
}

export interface CreateDeliveryOrderLineRequest {
  itemDataId: number;
  amount: number;
  lotNumber?: string;
}

export interface CreateDeliveryOrderRequest {
  /** Generated (DO-prefixed) when omitted */
  orderNumber?: string;
  customerName?: string;
  externalNumber?: string;
  /** ISO date (yyyy-MM-dd) */
  deliveryDate?: string;
  /** myWMS convention: 50 = NORMAL */
  prio?: number;
  notes?: string;
  pickingHint?: string;
  packingHint?: string;
  shippingHint?: string;
  orderStrategyId?: number;
  /** Ship-to address (optional; rendered on shipping documents in v1.3 3.4b). */
  street?: string;
  streetNumber?: string;
  zipCode?: string;
  city?: string;
  country?: string;
  phone?: string;
  email?: string;
  /** Row 10: which StorageLocation inside this warehouse the order's work is bound for. */
  destinationLocationId?: number;
  /** Row 10: the party named as sender on this order's outbound paperwork. */
  senderName?: string;
  lines: CreateDeliveryOrderLineRequest[];
}

/**
 * Pre-release header edits only — backend rejects with 409 unless state is CREATED.
 *
 * `notes`/`pickingHint`/`packingHint`/`shippingHint`/`externalNumber`/`destinationLocationId`/
 * `senderName` are tri-state on the backend (`Patchable<T>`; the string fields since D2
 * 2026-07-25, `destinationLocationId`/`senderName` since :1457 2026-08-17): key omitted =
 * leave unchanged, explicit `null` = clear, a value = set. `undefined` here maps to "omit the
 * key" (matches `JSON.stringify` dropping `undefined` properties); the order-edit form's
 * payload builders (`patchField`/`patchIdField` in `order-form.tsx`) are what actually send
 * `null` for a cleared field.
 */
export interface UpdateDeliveryOrderRequest {
  customerName?: string;
  externalNumber?: string | null;
  deliveryDate?: string;
  prio?: number;
  notes?: string | null;
  pickingHint?: string | null;
  packingHint?: string | null;
  shippingHint?: string | null;
  orderStrategyId?: number;
  /** Row 10: which StorageLocation inside this warehouse the order's work is bound for. */
  destinationLocationId?: number | null;
  /** Row 10: the party named as sender on this order's outbound paperwork. */
  senderName?: string | null;
}

export interface LineShortage {
  lineId: number;
  lineNumber: number;
  itemDataId: number;
  itemDataNumber: string;
  requestedAmount: number;
  reservedAmount: number;
  shortfall: number;
}

/** Result of release / retry-reservation: updated order + shortage report. */
export interface DeliveryOrderReleaseResponse {
  order: DeliveryOrderResponse;
  shortages: LineShortage[];
}

export interface OrderStrategyResponse {
  id: number;
  name: string;
  useLockedStock: boolean;
  preferComplete: boolean;
  preferMatching: boolean;
  completeHandling: number;
  enforceLot: boolean;
  shortPickMode: string;
  shortfallStrategy: string;
  pickDifferenceStrategy: string;
  packoutStrategy: string;
  extensionProperties: Record<string, unknown>;
  /** Row 8: a picked order parks in PACKING(640) instead of stopping at PICKED(600). */
  sendToPacking: boolean;
  /** Row 8: a packed order parks in SHIPPING(670) instead of stopping at PACKED(650). */
  sendToShipping: boolean;
  /** Row 8: pick-order completion auto-opens the shipment (Karyo-native, not a myWMS parity claim). */
  createShippingOrder: boolean;
  /** Row 8: `releaseToPicking` splits a mixed release into one PickOrder per picking type. */
  createTypeOrders: boolean;
  /** Row 8: fallback destination when the order itself has none. */
  defaultDestinationLocationId: number | null;
  /** Row 8: resolved display name for defaultDestinationLocationId. */
  defaultDestinationLocationName: string | null;
}

/**
 * OrderState codes wired in v1.2 (full 18-state machine lives in the backend;
 * the list filter only offers states an order can actually be in today).
 * @see services/order-service/karyo-orders-api/src/main/kotlin/com/karyo/orders/vo/OrderState.kt
 */
export const ORDER_STATES = [
  { code: 50, name: 'Created' },
  { code: 100, name: 'Released' },
  { code: 300, name: 'Processable' },
  { code: 550, name: 'Pending' },
  { code: 800, name: 'Canceled' },
] as const;

/** OrderState codes used by the UI logic. */
export const ORDER_STATE = {
  CREATED: 50,
  RELEASED: 100,
  PROCESSABLE: 300,
  PENDING: 550,
  PICKED: 600,
  CANCELED: 800,
} as const;

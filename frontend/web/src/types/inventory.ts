/**
 * TypeScript interfaces matching inventory-service DTOs.
 * @see services/inventory-service/karyo-inventory-api/src/main/kotlin/com/karyo/inventory/api/dto/StockUnitDto.kt
 */

export interface StockUnitResponse {
  id: number;
  itemDataId: number;
  itemDataNumber: string;   // Product SKU
  /** Product name via ProductLookup; null when the product is missing/foreign-tenant (honest gap — fall back to itemDataNumber). */
  itemDataName: string | null;
  amount: number;
  reservedAmount: number;
  availableAmount: number;  // = amount - reservedAmount
  serialNumber: string | null;
  lotNumber: string | null;
  bestBefore: string | null;
  state: number;
  stateName: string;        // e.g., "ON_STOCK", "INCOMING"
  lockType: number;
  lockTypeName: string;
  strategyDate: string | null;
  unitLoadId: number;
  unitLoadLabel: string;
  locationId: number;
  locationName: string;
  created: string;
  modified: string;
  /** Supplier via the backend's GoodsReceiptLookup batch join; null when the unit was not received through a GR (honest gap). */
  supplierName: string | null;
  /** Source ASN number via the same lookup; null = honest gap (blind receipt or not GR-sourced). */
  sourceAsn: string | null;
  /** Goods-receipt received-at timestamp (ISO), via the same lookup; null = honest gap. */
  receivedAt: string | null;
  /** The unit load's UnitLoadType.aggregateStocks — true = loose/aggregated, false = discrete LPN-tracked. */
  aggregateStocks: boolean;
  /** A5: ID-only reference to the product module's PackagingUnit, validated at creation via PackagingUnitLookup. */
  packagingUnitId: number | null;
}

export interface StockUnitSummary {
  id: number;
  itemDataNumber: string;
  amount: number;
  lotNumber: string | null;
  state: number;
}

export interface UnitLoadResponse {
  id: number;
  labelId: string;
  externalId: string | null;
  unitLoadTypeId: number;
  unitLoadTypeName: string;
  storageLocationId: number;
  storageLocationName: string;
  state: number;
  opened: boolean;
  isCarrier: boolean;
  weight: number | null;
  /** A2: pallet-level lock code (0 = unlocked); LockType.GENERAL(1) is the default applied by lock/transfer-to-clearing. */
  lockType: number;
  lockTypeName: string;
  stockUnits: StockUnitSummary[];
  created: string;
}

export interface CreateUnitLoadRequest {
  labelId: string;
  unitLoadTypeId: number;
  storageLocationId: number;
  storageLocationName: string;
}

export interface TransferUnitLoadRequest {
  destinationLocationId: number;
  destinationLocationName: string;
  activityCode?: string;
}

export interface CreateStockUnitRequest {
  itemDataId: number;
  itemDataNumber: string;
  amount: number;
  unitLoadId: number;
  state?: number;
}

export interface AdjustStockRequest {
  newAmount: number;
  activityCode: string;
}

export interface SetLockRequest {
  lockType: number;
  reason?: string;
}

/** POST /api/v1/unit-loads/{id}/change-client — OPS-principal-gated server-side. */
export interface ChangeClientRequest {
  targetClientId: number;
  activityCode?: string;
}

/** POST /api/v1/unit-loads/{id}/carrier */
export interface SetCarrierRequest {
  isCarrier: boolean;
}

/**
 * A2-3: pallet-level lock request. `lockType` defaults to `LockType.GENERAL(1)`
 * server-side when omitted (the legacy CLEARING lock values are dead and never carried over).
 */
export interface LockUnitLoadRequest {
  lockType?: number;
  note?: string;
}

/** A2-1: request body for `POST /{id}/transfer-to-clearing` — note is optional context for the lock. */
export interface TransferToClearingRequest {
  note?: string;
}

export interface UnitLoadTypeResponse {
  id: number;
  name: string;
  usages: string | null;
  aggregateStocks: boolean;
  height: number | null;
  width: number | null;
  depth: number | null;
  liftingCapacity: number | null;
  /** Tare weight: the empty container's own weight, not a capacity. Feeds unit-load weight = tare + contents. */
  weight: number | null;
  /** Row 16: when true, an emptied container of this type is kept for reuse instead of being retired automatically. */
  manageEmpties: boolean;
  created: string;
  modified: string;
}

export interface CreateUnitLoadTypeRequest {
  name: string;
  usages?: string;
  aggregateStocks?: boolean;
  height?: number;
  width?: number;
  depth?: number;
  liftingCapacity?: number;
  weight?: number;
  manageEmpties?: boolean;
}

/**
 * Row 16: PUT /api/v1/unit-load-types/{id} is a full-representation update. Any field
 * omitted from the request body is reset to its backend default, not left unchanged.
 * Every field here is required (nullable, not optional) so a caller cannot build this
 * object by only setting the fields the user touched; the compiler forces the full
 * shape on every save.
 */
export interface UpdateUnitLoadTypeRequest {
  name: string;
  usages: string | null;
  aggregateStocks: boolean;
  height: number | null;
  width: number | null;
  depth: number | null;
  liftingCapacity: number | null;
  weight: number | null;
  manageEmpties: boolean;
}

/** StockState values for filter dropdown */
export const STOCK_STATES = [
  { code: 0, name: 'Undefined' },
  { code: 100, name: 'Incoming' },
  { code: 300, name: 'On Stock' },
  { code: 600, name: 'Picked' },
  { code: 650, name: 'Packed' },
  { code: 680, name: 'Shipped' },
  { code: 1000, name: 'Deletable' },
] as const;

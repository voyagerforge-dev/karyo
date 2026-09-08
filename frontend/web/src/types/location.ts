/**
 * TypeScript interfaces matching warehouse-layout-service DTOs.
 * @see services/warehouse-layout-service/karyo-layout-api/src/main/kotlin/com/karyo/layout/dto/
 */

export interface ZoneResponse {
  id: number;
  name: string;
  description: string | null;
  overflowZoneId: number | null;
  created: string;
  modified: string;
}

export interface AreaResponse {
  id: number;
  name: string;
  usages: string[];
  created: string;
  modified: string;
}

export interface LocationClusterResponse {
  id: number;
  name: string;
  parentClusterId: number | null;
  created: string;
  modified: string;
}

export interface LocationTypeResponse {
  id: number;
  name: string;
  height: number | null;
  width: number | null;
  depth: number | null;
  liftingCapacity: number | null;
  created: string;
  modified: string;
}

export interface LocationResponse {
  id: number;
  name: string;
  scanCode: string | null;
  locationType: LocationTypeResponse;
  area: AreaResponse;
  zone: ZoneResponse | null;
  locationCluster: LocationClusterResponse | null;
  allocation: number;
  lockType: number;
  lockTypeName: string;
  orderIndex: number;
  xPos: number;
  yPos: number;
  zPos: number;
  rack: string | null;
  field: string | null;
  section: string | null;
  created: string;
  modified: string;
  capacity: number | null;
  temperatureZone: string | null;
  handlingClass: string | null;
  kind: string | null;
  lastCountedAt: string | null;
  /** A2-1: explicit, queryable clearing-location flag (at most one true per instance). */
  isClearing: boolean;
  /** L3 (locations-layout sprint): free-text automation-system (PLC/WCS) address. */
  plcCode: string | null;
  /** L3: 0 = normal/searchable; non-zero = operator-excluded from the putaway finder. */
  allocationState: number;
}

/** Lock type constants: 0=NONE, 1=STOCKTAKING, 2=QUARANTINE, 3=QUALITY_FAULT, 4=DAMAGE */
export const LOCK_TYPES = [
  { code: 0, name: 'None' },
  { code: 1, name: 'Stocktaking' },
  { code: 2, name: 'Quarantine' },
  { code: 3, name: 'Quality Fault' },
  { code: 4, name: 'Damage' },
] as const;

export interface CreateZoneRequest {
  name: string;
  description?: string;
  overflowZoneId?: number;
}

export interface CreateAreaRequest {
  name: string;
  usages?: string[];
}

export interface CreateLocationRequest {
  name: string;
  scanCode?: string;
  locationTypeId: number;
  areaId: number;
  zoneId?: number;
  locationClusterId?: number;
  orderIndex?: number;
  xPos?: number;
  yPos?: number;
  zPos?: number;
  rack?: string;
  field?: string;
  section?: string;
  /** L3 (locations-layout sprint): free-text automation-system (PLC/WCS) address. */
  plcCode?: string;
  /** L3: 0 = normal/searchable; non-zero excludes the location from the putaway finder. */
  allocationState?: number;
  /** Task 10: V309 seeder-derived metadata, now settable at create time. */
  capacity?: number;
  temperatureZone?: string;
  handlingClass?: string;
  kind?: string;
  /** Task 10: A2-1's isClearing was update-only; now settable at create time too. */
  isClearing?: boolean;
}

export interface CreateLocationClusterRequest {
  name: string;
  parentClusterId?: number;
}

export interface LockLocationRequest {
  lockType: number;
  reason?: string;
}

export interface CreateLocationTypeRequest {
  name: string;
  height?: number;
  width?: number;
  depth?: number;
  liftingCapacity?: number;
}

/**
 * A fixed item→location assignment; `minAmount` is the reorder point for
 * that item at that location.
 * @see services/warehouse-layout-service/karyo-layout-api/src/main/kotlin/com/karyo/layout/dto/FixAssignmentResponse.kt
 */
export interface FixAssignmentResponse {
  id: number;
  locationId: number;
  locationName: string;
  itemDataId: number;
  itemDataNumber: string | null;
  minAmount: number | null;
  maxAmount: number | null;
  desiredAmount: number | null;
  currentStockAmount: number | null;
  orderIndex: number;
  created: string;
  modified: string;
}

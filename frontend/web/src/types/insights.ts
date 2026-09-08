export type ReportRange = '7D' | '30D' | '90D' | 'YTD';
export const RANGES: ReportRange[] = ['7D', '30D', '90D', 'YTD'];

export interface RangePoint { day: string; value: number; }

export interface KpiTile {
  key: 'accuracy' | 'throughput' | 'cycleTime' | 'utilization';
  label: string;
  /** Pre-formatted string from the backend — render verbatim. */
  value: string;
  /** Pre-formatted delta string, or null when not applicable (e.g. utilization). */
  delta: string | null;
  tone: 'up' | 'warning';
  series: RangePoint[];
}

export interface KpiChart { outbound: RangePoint[]; received: RangePoint[]; }

export interface KpiDashboardResponse {
  range: string;
  rangeLabel: string;
  tiles: KpiTile[];
  chart: KpiChart;
}

// ---------------------------------------------------------------------------
// Occupancy heatmap types
// ---------------------------------------------------------------------------

export type OccupancyState = 'occupied' | 'empty' | 'locked';

export interface OccupancyCell {
  id: number;
  name: string;
  state: OccupancyState;
  /** Slot capacity (nullable — Phase-B honest metadata; not every location has one set). */
  capacity?: number | null;
  /** Live (non-DELETABLE) unit-load count on this location. */
  unitLoadCount?: number;
}

export interface OccupancyZone {
  zoneId: number | null;
  zoneName: string;
  occupied: number;
  total: number;
  pct: number;
  locations: OccupancyCell[];
  /** Σ capacity over this zone's capacitied locations; null when none of them have capacity set. */
  capacitySlots?: number | null;
  /** Σ unit-load count over those same capacitied locations. */
  usedSlots?: number | null;
}

export interface OccupancyTotals {
  occupied: number;
  total: number;
  pct: number;
  /** Σ capacity over locations with a non-null capacity. */
  capacitySlots?: number;
  /** Σ unit-load count over those same capacitied locations. */
  usedSlots?: number;
  /** Count of locations that carry a non-null capacity. */
  locationsWithCapacity?: number;
  /** usedSlots / capacitySlots — null when no location has a capacity set. */
  utilization?: number | null;
}

export interface OccupancyResponse {
  zones: OccupancyZone[];
  unzoned: OccupancyZone | null;
  totals: OccupancyTotals;
}

// ---------------------------------------------------------------------------
// Volume-by-category (Phase B / B18)
// ---------------------------------------------------------------------------

export interface CategoryVolume {
  category: string;
  /** Pick volume — backend returns a numeric BigDecimal, serialized as a JSON number. */
  volume: number;
  lineCount: number;
}

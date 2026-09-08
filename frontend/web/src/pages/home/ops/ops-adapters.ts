import type { AlertDto, BackendSeverity } from '@/pages/monitors/monitors-api';
import type { KpiDashboardResponse, OccupancyResponse, OccupancyState, RangePoint } from '@/types/insights';

/**
 * Pure real-data adapters for the home "Operations Control" dashboard.
 *
 * These turn real API response shapes (`/insights/kpis`, `/insights/occupancy`,
 * `/alerts`) into the exact presentational shapes the tile components
 * (`kpi-strip.tsx`, `throughput-card.tsx`, `zone-heatmap.tsx`,
 * `exceptions-card.tsx`) render. The interfaces below were originally copied
 * (not imported) from the now-deleted `use-ops-console.ts` mock module — the
 * tiles were rewired to import these types directly once `useOpsData` landed
 * (Task 3).
 *
 * No fabrication: every field here is derived from a real response field.
 * Fields that only exist in the mock (order-fill/on-time KPI cells, hourly
 * "Shift" throughput, pick-density/replen-need zone metrics) are dropped —
 * there is no backend for them yet.
 */

/* -------------------------------------------------------------------------- */
/* KPI strip                                                                  */
/* -------------------------------------------------------------------------- */

export type DeltaTone = 'up' | 'warning' | 'danger';

export interface KpiCell {
  label: string;
  value: string;
  /** Dimmed unit suffix (e.g. "%", "m") or undefined. */
  unit?: string;
  /** Whether the big value paints in the danger color. */
  valueDanger?: boolean;
  /** Sparkline polyline points, 58x24 viewbox. Empty string when no series data. */
  points: string;
  tone: DeltaTone;
  deltaArrow: '▲' | '▼';
  deltaText: string;
  context: string;
}

const SPARK_WIDTH = 58;
const SPARK_HEIGHT = 24;
const SPARK_PAD = 2;

/** Normalizes a KPI series into a `58x24` SVG polyline `points` string. */
function seriesToPoints(series: RangePoint[]): string {
  if (series.length === 0) return '';
  const values = series.map((p) => p.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  return series
    .map((p, i) => {
      const x = series.length === 1 ? 0 : (i / (series.length - 1)) * SPARK_WIDTH;
      const norm = (p.value - min) / range;
      const y = SPARK_HEIGHT - SPARK_PAD - norm * (SPARK_HEIGHT - SPARK_PAD * 2);
      return `${Math.round(x)},${Math.round(y)}`;
    })
    .join(' ');
}

/** Splits a pre-formatted delta string (e.g. "+0.3", "-2m") into arrow + sign-free text. */
function parseDelta(delta: string | null): { arrow: '▲' | '▼'; text: string } {
  if (!delta) return { arrow: '▲', text: '' };
  const trimmed = delta.trim();
  const arrow = trimmed.startsWith('-') ? '▼' : '▲';
  return { arrow, text: trimmed.replace(/^[+-]/, '') };
}

/**
 * Maps the 4 real KPI tiles (accuracy/throughput/cycleTime/utilization) into
 * `KpiCell`s, plus an optional 5th "Open exceptions" cell sourced from the
 * monitors firing count. `openExceptions` is `null` when monitors aren't
 * entitled — the cell is omitted rather than fabricated.
 */
export function kpisToCells(res: KpiDashboardResponse, openExceptions: number | null): KpiCell[] {
  const cells: KpiCell[] = res.tiles.map((tile) => {
    const { arrow, text } = parseDelta(tile.delta);
    return {
      label: tile.label,
      value: tile.value,
      points: seriesToPoints(tile.series),
      tone: tile.tone,
      deltaArrow: arrow,
      deltaText: text,
      context: '',
    };
  });

  if (openExceptions !== null) {
    cells.push({
      label: 'Open exceptions',
      value: String(openExceptions),
      valueDanger: openExceptions > 0,
      points: '',
      tone: openExceptions > 0 ? 'danger' : 'up',
      deltaArrow: '▲',
      deltaText: '',
      context: '',
    });
  }

  return cells;
}

/* -------------------------------------------------------------------------- */
/* Throughput chart                                                           */
/* -------------------------------------------------------------------------- */

export interface ThroughputBar {
  label: string;
  /** Bar height as a percentage (min 8, so a bar is always visible). */
  pct: number;
  isPeak: boolean;
}

export interface ThroughputView {
  bars: ThroughputBar[];
  peakText: string;
  avgText: string;
}

/**
 * Builds daily throughput bars from `chart.outbound`. There is no hourly
 * "Shift" backing series — only the daily view is real, so the Shift/Day
 * toggle concept from the mock is dropped here.
 */
export function kpiChartToThroughput(res: KpiDashboardResponse): ThroughputView {
  const series = res.chart.outbound;
  if (series.length === 0) {
    return { bars: [], peakText: 'PEAK —', avgText: 'AVG —' };
  }

  const values = series.map((p) => p.value);
  const max = Math.max(...values);
  const peakIdx = values.indexOf(max);
  const avg = Math.round(values.reduce((a, b) => a + b, 0) / values.length);

  const bars: ThroughputBar[] = series.map((p) => ({
    label: p.day,
    pct: max === 0 ? 8 : Math.max(8, Math.round((p.value / max) * 100)),
    isPeak: p.value === max,
  }));

  return {
    bars,
    peakText: `PEAK ${series[peakIdx].day.toUpperCase()} ${max.toLocaleString()}`,
    avgText: `AVG ${avg.toLocaleString()}`,
  };
}

/* -------------------------------------------------------------------------- */
/* Zone occupancy heatmap                                                     */
/* -------------------------------------------------------------------------- */

export interface ZoneCell {
  /** Fill color hex / css value. */
  color: string;
  /** Tooltip text. */
  title: string;
}

export interface ZoneField {
  cells: ZoneCell[];
  title: string;
  sub: string;
  badgeText: string;
  badgeTone: 'danger' | 'accent';
  legendUnit: string;
  legend: { color: string; label: string }[];
}

const ZONE_COLOR: Record<OccupancyState, string> = {
  occupied: 'rgb(var(--acc))',
  empty: 'var(--accent)',
  locked: 'var(--danger)',
};

/**
 * Flattens every real location (across zones + unzoned) into one cell per
 * location, colored by its actual occupancy state. There is no pick-density
 * or replen-need backing metric, so only the occupancy view exists here —
 * the mock's 3-metric switcher is dropped.
 */
export function occupancyToZoneField(res: OccupancyResponse): ZoneField {
  const zones = res.unzoned ? [...res.zones, res.unzoned] : res.zones;
  const locations = zones.flatMap((zone) => zone.locations.map((loc) => ({ zone, loc })));

  const cells: ZoneCell[] = locations.map(({ zone, loc }) => ({
    color: ZONE_COLOR[loc.state],
    title: `${zone.zoneName} · ${loc.name} — ${loc.state}`,
  }));

  const lockedCount = locations.filter(({ loc }) => loc.state === 'locked').length;

  return {
    cells,
    title: 'Zone occupancy',
    sub: `FACILITY ${res.totals.pct}% FULL`,
    badgeText: `${lockedCount} LOCKED`,
    badgeTone: lockedCount > 0 ? 'danger' : 'accent',
    legendUnit: 'STATE',
    legend: [
      { color: ZONE_COLOR.occupied, label: 'Occupied' },
      { color: ZONE_COLOR.empty, label: 'Empty' },
      { color: ZONE_COLOR.locked, label: 'Locked' },
    ],
  };
}

/* -------------------------------------------------------------------------- */
/* Exceptions                                                                 */
/* -------------------------------------------------------------------------- */

export type ExceptionSeverity = 'danger' | 'warning' | 'info';

export interface ExceptionRow {
  type: string;
  detail: string;
  age: string;
  severity: ExceptionSeverity;
}

const SEVERITY_MAP: Record<BackendSeverity, ExceptionSeverity> = {
  HIGH: 'danger',
  MEDIUM: 'warning',
  LOW: 'info',
};

/** `"2m"` / `"3h"` / `"1d"` style relative age, no "ago" suffix (matches the tile's compact style). */
function relativeAge(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.max(0, Math.round(diffMs / 60_000));
  if (mins < 1) return '<1m';
  if (mins < 60) return `${mins}m`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.round(hours / 24);
  return `${days}d`;
}

/** Maps firing alerts into exception rows. HIGH -> danger, MEDIUM -> warning, LOW -> info. */
export function alertsToExceptions(alerts: AlertDto[]): ExceptionRow[] {
  return alerts.map((a) => ({
    type: a.monitorName.toUpperCase(),
    detail: a.scope ? `${a.scope} · ${a.reason}` : a.reason,
    age: relativeAge(a.firstFiredAt),
    severity: SEVERITY_MAP[a.severity],
  }));
}

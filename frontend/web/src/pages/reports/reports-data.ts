// Reports view-model types.
//
// "Volume by category" and "Saved reports" were honest-empty stubs pending backend
// support; both now render real data (GET /api/v1/insights/volume-by-category — B18,
// GET/POST/DELETE /api/v1/report-definitions — B19), still honest-empty when there is
// none. Only the trend-chart SVG geometry lives here — it is computed from the real
// GET /api/v1/insights/kpis data.

/** SVG geometry strings for the trend chart (computed from live KpiChart data). */
export interface TrendChart {
  /** lime polyline (picked/outbound). */
  lineShip: string;
  /** blue polyline (received). */
  lineRecv: string;
  /** lime area polygon under picked line. */
  areaShip: string;
  /** x-axis labels under the chart. */
  axis: string[];
}

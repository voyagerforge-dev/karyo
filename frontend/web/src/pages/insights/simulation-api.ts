import { api } from '@/lib/api-client';

/**
 * Per-SKU reorder what-if backtest (naive vs. safety-stock policy replayed over historical
 * demand). Mirrors backend `ReorderSimResultDto` (`GET /api/v1/simulations/reorder`, VIEWER,
 * tenant-scoped). Server-presorted by `stockoutDaysAvoided` desc. Gated behind the
 * `simulation` paid license (`useLicense().isEntitled('simulation')`) — see `simulation-page.tsx`,
 * which never calls this endpoint when unentitled.
 */
export interface ReorderSimRow {
  sku: string;
  confidence: string;
  baselineStockoutDays: number;
  suggestedStockoutDays: number;
  stockoutDaysAvoided: number;
  baselineFillRate: number;
  suggestedFillRate: number;
  fillRateDelta: number;
  baselineAvgOnHand: number;
  suggestedAvgOnHand: number;
  avgOnHandDelta: number;
  baselineReorderPoint: number;
  suggestedReorderPoint: number;
  orderQty: number;
}

export interface ReorderSimSummary {
  skusSimulated: number;
  totalStockoutDaysAvoided: number;
  avgFillRateDelta: number;
  totalAvgOnHandDelta: number;
}

export interface ReorderSimResponse {
  summary: ReorderSimSummary;
  rows: ReorderSimRow[];
}

export const getSimulation = () => api.get<ReorderSimResponse>('/api/v1/simulations/reorder');

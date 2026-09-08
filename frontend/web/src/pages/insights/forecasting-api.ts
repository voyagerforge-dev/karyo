import { api } from '@/lib/api-client';

/**
 * Per-SKU reorder suggestion. Mirrors backend `SkuForecastDto`
 * (`GET /api/v1/forecasts`, inventory-read, tenant-scoped). Sorted by the
 * backend: `belowReorderPoint` rows first, then `forecastNextNDays` desc.
 * An empty tenant returns `200 []` (not 404). Gated behind the `forecasting`
 * paid license (`useLicense().isEntitled('forecasting')`) — see
 * `forecasting-page.tsx`, which never calls this endpoint when unentitled.
 */
export interface SkuForecast {
  sku: string;
  avgDailyDemand: number;
  forecastNextNDays: number;
  currentOnHand: number;
  suggestedReorderPoint: number;
  suggestedReorderQty: number;
  belowReorderPoint: boolean;
  confidence: string;
}

export const getForecasts = () => api.get<SkuForecast[]>('/api/v1/forecasts');

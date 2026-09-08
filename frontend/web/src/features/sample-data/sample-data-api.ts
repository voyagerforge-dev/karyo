/**
 * Sample-data API module -- thin wrapper over the backend `karyo-demo` engine.
 *
 * The demo warehouse generation and teardown are entirely server-side
 * (`POST /api/v1/demo/seed` / `POST /api/v1/demo/reset`, ADMIN or MANAGER,
 * gated behind `KARYO_DEMO=on` -- 404s when disabled). This module used to make
 * ~30 per-entity REST calls to build a small static warehouse; that logic
 * has been replaced by the single backdated "living warehouse" the backend
 * generates (see AGENTS.md's `karyo-demo` gotcha).
 *
 * This is NOT a React hook -- it is a plain async API module.
 */
import { api } from '@/lib/api-client';

/** Mirrors the backend `DemoSeedSummary` DTO returned by `POST /api/v1/demo/seed`. */
export interface DemoSeedSummary {
  locations: number;
  skus: number;
  orders: number;
  picks: number;
  shipments: number;
  counts: number;
  goodsReceipts: number;
  transportOrders: number;
  alertsTripped: string[];
}

export const sampleDataApi = {
  seed: () => api.post<DemoSeedSummary>('/api/v1/demo/seed', {}),
  reset: () => api.post<void>('/api/v1/demo/reset', {}),
};

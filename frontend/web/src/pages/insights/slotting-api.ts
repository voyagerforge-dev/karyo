import { api } from '@/lib/api-client';

/**
 * Per-SKU re-slot recommendation. Mirrors backend `ReSlotSuggestionDto`
 * (`GET /api/v1/slotting/recommendations`, inventory-read, tenant-scoped).
 * Server-presorted: `PROMOTE` rows first, then `severity` desc. An empty
 * tenant returns `200 []` (not 404). Gated behind the `slotting` paid
 * license (`useLicense().isEntitled('slotting')`) — see `slotting-page.tsx`,
 * which never calls this endpoint when unentitled.
 */
export interface ReSlotSuggestion {
  sku: string;
  abcClass: string;
  velocityRank: number;
  currentLocation: string;
  currentOrderIndex: number;
  direction: string;
  reason: string;
  severity: number;
}

export const getSlotting = () => api.get<ReSlotSuggestion[]>('/api/v1/slotting/recommendations');

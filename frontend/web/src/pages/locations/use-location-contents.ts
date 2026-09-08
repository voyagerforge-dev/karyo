import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type { StockUnitResponse } from '@/types/inventory';

/** One "Stored here" row — real stock-unit data. `desc` is the product name
 *  via the backend's ProductLookup batch join; honest empty ('') when the
 *  product is missing/foreign-tenant (never fabricated). */
export interface ContentRow {
  sku: string;
  desc: string;
  lot: string;
  qty: number;
  status: 'Pickable' | 'Reserved' | 'QA hold';
}

/** Pure: fold stock units into per-location content rows + a used (sum-qty) map. */
export function groupContents(units: StockUnitResponse[]): {
  byLocation: Map<number, ContentRow[]>;
  usedByLocation: Map<number, number>;
} {
  const byLocation = new Map<number, ContentRow[]>();
  const usedByLocation = new Map<number, number>();
  for (const u of units) {
    // "Stored here" = inventory actually resident in the bin. Exclude units that
    // have left it in the outbound flow (PICKED 600 / PACKED 650 / SHIPPED 680);
    // INCOMING(100)/ON_STOCK(300) remain. (B24)
    if (u.state >= 600) continue;
    const status: ContentRow['status'] =
      u.lockType !== 0 ? 'QA hold' : u.reservedAmount > 0 ? 'Reserved' : 'Pickable';
    const row: ContentRow = {
      sku: u.itemDataNumber,
      desc: u.itemDataName ?? '',
      lot: u.lotNumber ?? '—',
      qty: u.amount,
      status,
    };
    const rows = byLocation.get(u.locationId);
    if (rows) rows.push(row);
    else byLocation.set(u.locationId, [row]);
    usedByLocation.set(u.locationId, (usedByLocation.get(u.locationId) ?? 0) + u.amount);
  }
  return { byLocation, usedByLocation };
}

/**
 * Real "Stored here" contents for every location, from one stock-units query
 * (size=500 covers the demo catalog; shares the React Query cache with
 * use-item-stock). A larger warehouse would need real pagination here (B21:
 * the grouped item×location view intentionally fetches wide — pagination would
 * fragment the grouping; documented as a known constraint, not a silent cap).
 */
export function useLocationContents(): {
  byLocation: Map<number, ContentRow[]>;
  usedByLocation: Map<number, number>;
  isLoading: boolean;
} {
  const { data, isLoading } = useQuery({
    queryKey: ['stock-units', { page: 0, size: 500 }],
    queryFn: () =>
      api.get<PaginatedResponse<StockUnitResponse>>('/api/v1/stock-units?page=0&size=500'),
    staleTime: 30_000,
  });
  const { byLocation, usedByLocation } = groupContents(data?.content ?? []);
  return { byLocation, usedByLocation, isLoading };
}

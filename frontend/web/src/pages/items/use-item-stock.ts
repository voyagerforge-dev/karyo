import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type { StockUnitResponse } from '@/types/inventory';

/**
 * Real per-item stock position, aggregated from LPN-level stock units.
 *
 * Used by both the Items list (availability at a glance) and the Item
 * detail pane (stock position + where-stored breakdown).
 */
export interface ItemStock {
  onHand: number;
  available: number;
  reserved: number;
  /** Sum of `amount` across units with `lockType !== 0`. */
  held: number;
  locations: Array<{ location: string; lpn: string; amount: number; state: string }>;
}

function emptyItemStock(): ItemStock {
  return { onHand: 0, available: 0, reserved: 0, held: 0, locations: [] };
}

/**
 * Fold stock units into per-item stock positions, keyed by `itemDataId`.
 * Pure function — mirrors the grouping pattern in
 * `pages/inventory/inventory-rows.ts` (`toItemLocationGroups`), but groups
 * by item alone (not item @ location) since this is item-scoped, not
 * location-scoped.
 */
export function aggregateStock(units: StockUnitResponse[]): Map<number, ItemStock> {
  const byItem = new Map<number, ItemStock>();

  for (const u of units) {
    let s = byItem.get(u.itemDataId);
    if (!s) {
      s = emptyItemStock();
      byItem.set(u.itemDataId, s);
    }

    s.onHand += u.amount;
    s.available += u.availableAmount;
    s.reserved += u.reservedAmount;
    if (u.lockType !== 0) s.held += u.amount;

    s.locations.push({
      location: u.locationName,
      lpn: u.unitLoadLabel,
      amount: u.amount,
      state: u.stateName,
    });
  }

  return byItem;
}

/**
 * Fetch all stock units in one generous page and aggregate them by item.
 * `size=500` comfortably covers the ~24-SKU demo catalog; a larger
 * warehouse would need real pagination here (out of scope for this task —
 * see `pages/inventory/inventory-page.tsx` for the paginated precedent).
 */
export function useItemStock(): { stockByItem: Map<number, ItemStock>; isLoading: boolean } {
  const { data, isLoading } = useQuery({
    queryKey: ['stock-units', { page: 0, size: 500 }],
    queryFn: () =>
      api.get<PaginatedResponse<StockUnitResponse>>('/api/v1/stock-units?page=0&size=500'),
    staleTime: 30_000,
  });

  const stockByItem = data ? aggregateStock(data.content) : new Map<number, ItemStock>();

  return { stockByItem, isLoading };
}

import type { StockUnitResponse } from '@/types/inventory';
import { classifyStatus, type StatusStyle } from './stock-status';

/**
 * Aggregate LPN-level stock units into Item @ Location groups.
 *
 * The v3 record is **Item @ Location**, not the LPN. The backend
 * `useStockUnits` returns one row per stock unit (LPN-level); we fold those
 * into groups keyed by `(itemDataId, locationId)`, summing quantities and
 * collecting the constituent LPNs and lots. LPNs are demoted to the detail
 * pane's "Stored units" sub-table.
 */

export interface GroupLpn {
  /** stock-unit id (stable key for the row). */
  id: number;
  /** The unit load this stock unit belongs to — target for Move. */
  unitLoadId: number;
  lpn: string;
  lot: string | null;
  bestBefore: string | null;
  daysLeft: number | null;
  qty: number;
}

export interface ItemLocationGroup {
  /** Stable composite key: `${itemDataId}@${locationId}`. */
  key: string;
  itemDataId: number;
  locationId: number;
  /**
   * Display name for the item, via the backend's ProductLookup batch join
   * (`itemDataName`). Falls back to the SKU when the product is missing or
   * belongs to another tenant (honest gap, not fabricated).
   */
  name: string;
  sku: string;
  location: string;
  /**
   * Honest gap: the backend has no location-type field on the stock-unit, so
   * this is "—". `lpnTracked` (below) is the real LPN-vs-loose signal, derived
   * from the unit load type's `aggregateStocks` flag.
   */
  locType: string;
  lpnTracked: boolean;
  onHand: number;
  reserved: number;
  available: number;
  /** Distinct lot numbers across the group (non-null). */
  lots: string[];
  /** Constituent LPN/stock-unit rows (only meaningful when lpnTracked). */
  lpns: GroupLpn[];
  /** Every constituent stock-unit id in the group — Adjust/Hold targets. */
  stockUnitIds: number[];
  /** Distinct unit-load ids across the group's constituents — Move targets. */
  unitLoadIds: number[];
  /** Earliest non-null bestBefore across the group, ISO date. */
  earliestExpiry: string | null;
  earliestDaysLeft: number | null;
  /** True when any constituent unit is locked (lockType !== 0). */
  held: boolean;
  status: StatusStyle;
  /**
   * Supplier/ASN/received-at from a representative constituent's
   * GoodsReceiptLookup join (the first constituent with a non-null
   * `receivedAt`, else the first constituent). Honest `—` (null) when no
   * constituent was received through a GR.
   */
  supplierName: string | null;
  sourceAsn: string | null;
  receivedAt: string | null;
  /**
   * Reorder point (FixAssignment.minAmount) for this item at this location;
   * null when there is no fix-assignment (honest gap — never fabricated).
   * Populated by `withReorderPoints`, not by `toItemLocationGroups`.
   */
  reorderPoint: number | null;
}

const MS_PER_DAY = 86_400_000;

/** Days from `now` until an ISO date; null when the date is null/invalid. */
export function daysUntil(iso: string | null, now: Date = new Date()): number | null {
  if (!iso) return null;
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return null;
  return Math.round((t - now.getTime()) / MS_PER_DAY);
}

function hasLpnLabel(label: string | null | undefined): boolean {
  return !!label && label.trim().length > 0;
}

/**
 * Fold stock units into Item @ Location groups, sorted by the grouping axis.
 * `now` is injectable for deterministic tests.
 */
export function toItemLocationGroups(
  units: StockUnitResponse[],
  now: Date = new Date(),
): ItemLocationGroup[] {
  const byKey = new Map<string, ItemLocationGroup>();
  // Per-group bookkeeping not exposed on ItemLocationGroup itself.
  const hasReceiptRepresentative = new Map<string, boolean>();
  const sawDiscreteUnitLoad = new Map<string, boolean>();

  for (const u of units) {
    const key = `${u.itemDataId}@${u.locationId}`;
    let g = byKey.get(key);
    if (!g) {
      g = {
        key,
        itemDataId: u.itemDataId,
        locationId: u.locationId,
        name: u.itemDataName ?? u.itemDataNumber,
        sku: u.itemDataNumber,
        location: u.locationName,
        locType: '',
        lpnTracked: false,
        onHand: 0,
        reserved: 0,
        available: 0,
        lots: [],
        lpns: [],
        stockUnitIds: [],
        unitLoadIds: [],
        earliestExpiry: null,
        earliestDaysLeft: null,
        held: false,
        status: { status: 'Out', tone: 'red' },
        supplierName: u.supplierName,
        sourceAsn: u.sourceAsn,
        receivedAt: u.receivedAt,
        reorderPoint: null,
      };
      byKey.set(key, g);
      hasReceiptRepresentative.set(key, u.receivedAt !== null);
    } else if (!hasReceiptRepresentative.get(key) && u.receivedAt !== null) {
      // A later constituent actually came through a GR — prefer it over the
      // first unit's (possibly honest-gapped) receipt fields.
      g.supplierName = u.supplierName;
      g.sourceAsn = u.sourceAsn;
      g.receivedAt = u.receivedAt;
      hasReceiptRepresentative.set(key, true);
    }

    g.onHand += u.amount;
    g.reserved += u.reservedAmount;
    g.available += u.availableAmount;
    if (u.lockType !== 0) g.held = true;
    if (u.lotNumber && !g.lots.includes(u.lotNumber)) g.lots.push(u.lotNumber);

    g.stockUnitIds.push(u.id);
    if (!g.unitLoadIds.includes(u.unitLoadId)) g.unitLoadIds.push(u.unitLoadId);

    // Real LPN-vs-loose signal: the unit load type's aggregateStocks flag.
    if (!u.aggregateStocks) sawDiscreteUnitLoad.set(key, true);

    if (hasLpnLabel(u.unitLoadLabel)) {
      g.lpns.push({
        id: u.id,
        unitLoadId: u.unitLoadId,
        lpn: u.unitLoadLabel,
        lot: u.lotNumber,
        bestBefore: u.bestBefore,
        daysLeft: daysUntil(u.bestBefore, now),
        qty: u.amount,
      });
    }

    // Track earliest expiry across constituents.
    const days = daysUntil(u.bestBefore, now);
    if (
      u.bestBefore &&
      days !== null &&
      (g.earliestDaysLeft === null || days < g.earliestDaysLeft)
    ) {
      g.earliestExpiry = u.bestBefore;
      g.earliestDaysLeft = days;
    }
  }

  for (const g of byKey.values()) {
    // lpnTracked is primarily the real signal (any constituent's unit-load
    // type does NOT aggregate stocks = discrete/LPN-tracked). Fall back to
    // the unit-load-label heuristic only if the real signal never fires
    // (e.g. every constituent aggregates but still carries individual labels).
    const distinctLpns = new Set(g.lpns.map((l) => l.lpn)).size;
    g.lpnTracked = (sawDiscreteUnitLoad.get(g.key) ?? false) || distinctLpns > 0;
    // Honest gap: the layout API models no location-type on the stock-unit, so
    // we do not assert one (Reserve pallet / Pick face was a fabricated stand-in).
    g.locType = '—';
    g.status = classifyStatus({
      onHand: g.onHand,
      available: g.available,
      reserved: g.reserved,
      held: g.held,
    });
  }

  return Array.from(byKey.values());
}

/**
 * Enrich groups with a reorder point (FixAssignment.minAmount) joined by the
 * group's `key` (`${itemDataId}@${locationId}`) — the exact axis a
 * fix-assignment binds an item to a location on. Pure; a separate pass from
 * `toItemLocationGroups` since fix-assignments load from a different query.
 */
export function withReorderPoints(
  groups: ItemLocationGroup[],
  reorderPoints: Map<string, number>,
): ItemLocationGroup[] {
  return groups.map((g) => ({
    ...g,
    reorderPoint: reorderPoints.get(g.key) ?? null,
  }));
}

export type GroupAxis = 'item' | 'location';

/** Sort groups by the active grouping axis (item name or location). */
export function sortGroups(
  groups: ItemLocationGroup[],
  axis: GroupAxis,
): ItemLocationGroup[] {
  return groups.slice().sort((a, b) =>
    axis === 'location'
      ? a.location.localeCompare(b.location) || a.name.localeCompare(b.name)
      : a.name.localeCompare(b.name) || a.location.localeCompare(b.location),
  );
}

/** Free-text match across name / sku / location / lots / LPNs. */
export function matchesQuery(g: ItemLocationGroup, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  if (g.name.toLowerCase().includes(q)) return true;
  if (g.sku.toLowerCase().includes(q)) return true;
  if (g.location.toLowerCase().includes(q)) return true;
  if (g.lots.some((l) => l.toLowerCase().includes(q))) return true;
  if (g.lpns.some((l) => l.lpn.toLowerCase().includes(q))) return true;
  return false;
}

/** Display lot for a group: "Mixed (n)" when multi-lot, else the lot or "—". */
export function groupLotLabel(g: ItemLocationGroup): string {
  if (g.lots.length > 1) return `Mixed (${g.lots.length})`;
  return g.lots[0] ?? '—';
}

export interface InventoryKpis {
  onHand: number;
  available: number;
  allocated: number;
  /** Groups with a real fix-assignment reorder point whose available qty has dropped below it. */
  needReorder: number;
  stockouts: number;
}

/** Page-scoped KPI totals computed from the aggregated groups. */
export function computeKpis(groups: ItemLocationGroup[]): InventoryKpis {
  return groups.reduce<InventoryKpis>(
    (acc, g) => {
      acc.onHand += g.onHand;
      acc.available += g.available;
      acc.allocated += g.reserved;
      // Distinct from the Out status below: reorder is judged against the
      // fix-assignment reorder point, not raw depletion.
      if (g.reorderPoint !== null && g.available < g.reorderPoint) acc.needReorder += 1;
      if (g.onHand <= 0) acc.stockouts += 1;
      return acc;
    },
    { onHand: 0, available: 0, allocated: 0, needReorder: 0, stockouts: 0 },
  );
}

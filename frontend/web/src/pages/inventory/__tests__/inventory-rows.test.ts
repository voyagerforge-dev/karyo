import { describe, it, expect } from 'vitest';
import type { StockUnitResponse } from '@/types/inventory';
import { computeKpis, toItemLocationGroups, withReorderPoints } from '../inventory-rows';

function su(p: Partial<StockUnitResponse> & Pick<StockUnitResponse, 'id'>): StockUnitResponse {
  return {
    id: p.id,
    itemDataId: p.itemDataId ?? 10,
    itemDataNumber: p.itemDataNumber ?? 'SKU-001',
    itemDataName: p.itemDataName ?? null,
    amount: p.amount ?? 100,
    reservedAmount: p.reservedAmount ?? 0,
    availableAmount: p.availableAmount ?? 100,
    serialNumber: null,
    lotNumber: p.lotNumber ?? null,
    bestBefore: p.bestBefore ?? null,
    state: p.state ?? 300,
    stateName: p.stateName ?? 'ON_STOCK',
    lockType: p.lockType ?? 0,
    lockTypeName: p.lockTypeName ?? 'NONE',
    strategyDate: null,
    unitLoadId: p.unitLoadId ?? 1,
    unitLoadLabel: p.unitLoadLabel ?? '',
    locationId: p.locationId ?? 1,
    locationName: p.locationName ?? 'A-01-01',
    created: '2026-03-01T00:00:00Z',
    modified: '2026-03-01T00:00:00Z',
    supplierName: p.supplierName ?? null,
    sourceAsn: p.sourceAsn ?? null,
    receivedAt: p.receivedAt ?? null,
    aggregateStocks: p.aggregateStocks ?? false,
    packagingUnitId: p.packagingUnitId ?? null,
  };
}

describe('toItemLocationGroups — mutation targets', () => {
  it('carries every constituent stock-unit id in stockUnitIds', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, unitLoadId: 100, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-1' }),
      su({ id: 2, unitLoadId: 101, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-2' }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].stockUnitIds.sort()).toEqual([1, 2]);
  });

  it('collects distinct unit-load ids across the group, deduplicated', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, unitLoadId: 100, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-1' }),
      // Same unit load contributes two stock units (e.g. two lots on one pallet).
      su({ id: 2, unitLoadId: 100, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-1', lotNumber: 'LOT-B' }),
      su({ id: 3, unitLoadId: 101, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-2' }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].stockUnitIds.sort()).toEqual([1, 2, 3]);
    expect(groups[0].unitLoadIds.sort()).toEqual([100, 101]);
  });

  it('propagates unitLoadId onto each GroupLpn row', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, unitLoadId: 55, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-55' }),
    ]);
    expect(groups[0].lpns).toHaveLength(1);
    expect(groups[0].lpns[0].unitLoadId).toBe(55);
  });

  it('a single-stock-unit group has exactly one stockUnitId and one unitLoadId', () => {
    const groups = toItemLocationGroups([
      su({ id: 7, unitLoadId: 200, locationId: 2, locationName: 'A2-C07' }),
    ]);
    expect(groups[0].stockUnitIds).toEqual([7]);
    expect(groups[0].unitLoadIds).toEqual([200]);
  });
});

describe('toItemLocationGroups — supplier / ASN / received', () => {
  it('carries supplier/sourceAsn/receivedAt from the first constituent with a non-null receipt', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, locationId: 1, locationName: 'B1-A09', supplierName: null, sourceAsn: null, receivedAt: null }),
      su({
        id: 2, locationId: 1, locationName: 'B1-A09',
        supplierName: 'Northwind Traders', sourceAsn: 'DEMO-ASN-00042', receivedAt: '2026-06-10T07:00:00Z',
      }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].supplierName).toBe('Northwind Traders');
    expect(groups[0].sourceAsn).toBe('DEMO-ASN-00042');
    expect(groups[0].receivedAt).toBe('2026-06-10T07:00:00Z');
  });

  it('honest-gaps supplier/sourceAsn/receivedAt when no constituent was received via a GR', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, locationId: 1, locationName: 'B1-A09' }),
      su({ id: 2, locationId: 1, locationName: 'B1-A09' }),
    ]);
    expect(groups[0].supplierName).toBeNull();
    expect(groups[0].sourceAsn).toBeNull();
    expect(groups[0].receivedAt).toBeNull();
  });
});

describe('toItemLocationGroups — lpnTracked from aggregateStocks', () => {
  it('is true when a constituent unit-load type does not aggregate stocks (discrete/LPN)', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, locationId: 1, locationName: 'B1-A09', unitLoadLabel: '', aggregateStocks: false }),
    ]);
    expect(groups[0].lpnTracked).toBe(true);
  });

  it('is false when every constituent aggregates stocks (loose), even with no label', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, locationId: 1, locationName: 'B1-A09', unitLoadLabel: '', aggregateStocks: true }),
    ]);
    expect(groups[0].lpnTracked).toBe(false);
  });

  it('falls back to the label heuristic when a constituent aggregates but still carries a label', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-1', aggregateStocks: true }),
    ]);
    expect(groups[0].lpnTracked).toBe(true);
  });
});

describe('withReorderPoints', () => {
  it('joins reorder points by the group key, honest-gapping missing entries', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, itemDataId: 10, locationId: 1, locationName: 'B1-A09' }),
      su({ id: 2, itemDataId: 11, locationId: 2, locationName: 'A2-C07' }),
    ]);
    const enriched = withReorderPoints(groups, new Map([['10@1', 25]]));
    const g1 = enriched.find((g) => g.key === '10@1')!;
    const g2 = enriched.find((g) => g.key === '11@2')!;
    expect(g1.reorderPoint).toBe(25);
    expect(g2.reorderPoint).toBeNull();
  });
});

describe('computeKpis — needReorder', () => {
  it('counts only groups with a real reorder point whose available qty is below it', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, itemDataId: 10, locationId: 1, locationName: 'B1-A09', amount: 5, availableAmount: 5 }),
      su({ id: 2, itemDataId: 11, locationId: 2, locationName: 'A2-C07', amount: 5, availableAmount: 5 }),
      su({ id: 3, itemDataId: 12, locationId: 3, locationName: 'A9-A03', amount: 100, availableAmount: 100 }),
    ]);
    const enriched = withReorderPoints(
      groups,
      new Map([
        ['10@1', 20], // available 5 < 20 -> needs reorder
        ['12@3', 20], // available 100 >= 20 -> does not
        // 11@2 has no fix-assignment -> honest gap, never counted
      ]),
    );
    const kpis = computeKpis(enriched);
    expect(kpis.needReorder).toBe(1);
  });

  it('does not conflate needReorder with the Out status (available <= 0 alone no longer counts)', () => {
    const groups = toItemLocationGroups([
      su({ id: 1, itemDataId: 10, locationId: 1, locationName: 'B1-A09', amount: 0, reservedAmount: 0, availableAmount: 0 }),
    ]);
    // No fix-assignment at all -> honest gap, not counted even though depleted.
    const kpis = computeKpis(withReorderPoints(groups, new Map()));
    expect(kpis.needReorder).toBe(0);
    expect(kpis.stockouts).toBe(1);
  });
});

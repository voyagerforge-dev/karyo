import { describe, it, expect } from 'vitest';
import { groupContents } from '../use-location-contents';
import type { StockUnitResponse } from '@/types/inventory';

function su(over: Partial<StockUnitResponse>): StockUnitResponse {
  return {
    id: 1, itemDataId: 1, itemDataNumber: 'SKU-1', itemDataName: null, amount: 10, reservedAmount: 0,
    availableAmount: 10, serialNumber: null, lotNumber: null, bestBefore: null,
    state: 300, stateName: 'ON_STOCK', lockType: 0, lockTypeName: 'None',
    strategyDate: null, unitLoadId: 1, unitLoadLabel: 'LPN-1', locationId: 5,
    locationName: 'A4·B12', created: '', modified: '',
    supplierName: null, sourceAsn: null, receivedAt: null, aggregateStocks: false,
    packagingUnitId: null,
    ...over,
  };
}

describe('groupContents', () => {
  it('groups stock units by locationId into ContentRows and sums used', () => {
    const { byLocation, usedByLocation } = groupContents([
      su({ id: 1, itemDataNumber: 'KX-1', lotNumber: 'LOT-9', amount: 8, locationId: 5 }),
      su({ id: 2, itemDataNumber: 'RT-2', amount: 3, reservedAmount: 3, locationId: 5 }),
      su({ id: 3, itemDataNumber: 'QA-3', amount: 4, lockType: 2, lockTypeName: 'Quarantine', locationId: 9 }),
    ]);

    expect(byLocation.get(5)).toEqual([
      { sku: 'KX-1', desc: '', lot: 'LOT-9', qty: 8, status: 'Pickable' },
      { sku: 'RT-2', desc: '', lot: '—', qty: 3, status: 'Reserved' },
    ]);
    expect(byLocation.get(9)).toEqual([
      { sku: 'QA-3', desc: '', lot: '—', qty: 4, status: 'QA hold' },
    ]);
    expect(usedByLocation.get(5)).toBe(11);
    expect(usedByLocation.get(9)).toBe(4);
  });

  it('uses itemDataName as desc when present, honest empty when null', () => {
    const { byLocation } = groupContents([
      su({ id: 1, itemDataNumber: 'KX-1', itemDataName: 'Widget Deluxe', locationId: 5 }),
      su({ id: 2, itemDataNumber: 'RT-2', itemDataName: null, locationId: 5 }),
    ]);

    expect(byLocation.get(5)).toEqual([
      { sku: 'KX-1', desc: 'Widget Deluxe', lot: '—', qty: 10, status: 'Pickable' },
      { sku: 'RT-2', desc: '', lot: '—', qty: 10, status: 'Pickable' },
    ]);
  });

  it('excludes units that have left the bin (PICKED/PACKED/SHIPPED, state >= 600)', () => {
    const { byLocation, usedByLocation } = groupContents([
      su({ id: 1, itemDataNumber: 'ON-1', locationId: 7, amount: 10, state: 300 }),
      su({ id: 2, itemDataNumber: 'PK-2', locationId: 7, amount: 4, state: 600, stateName: 'PICKED' }),
      su({ id: 3, itemDataNumber: 'PD-3', locationId: 7, amount: 5, state: 650, stateName: 'PACKED' }),
    ]);

    // only the ON_STOCK unit remains; used qty excludes the picked/packed ones
    expect(byLocation.get(7)).toEqual([
      { sku: 'ON-1', desc: '', lot: '—', qty: 10, status: 'Pickable' },
    ]);
    expect(usedByLocation.get(7)).toBe(10);
  });
});

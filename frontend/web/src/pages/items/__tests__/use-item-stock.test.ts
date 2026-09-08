import { describe, it, expect } from 'vitest';
import { aggregateStock, type ItemStock } from '../use-item-stock';
import type { StockUnitResponse } from '@/types/inventory';

const u = (o: Partial<StockUnitResponse>): StockUnitResponse => ({
  id: 1, itemDataId: 10, amount: 0, reservedAmount: 0, availableAmount: 0,
  state: 300, stateName: 'ON_STOCK', lockType: 0, lockTypeName: '',
  locationId: 1, locationName: 'A-01', unitLoadId: 1, unitLoadLabel: 'LPN1',
  lotNumber: null, bestBefore: null, serialNumber: null, ...o,
} as StockUnitResponse);

describe('aggregateStock', () => {
  it('sums amount/available/reserved and flags held (lockType != 0) per item', () => {
    const map = aggregateStock([
      u({ itemDataId: 10, amount: 100, reservedAmount: 20, availableAmount: 80 }),
      u({ itemDataId: 10, amount: 50, reservedAmount: 0, availableAmount: 50, lockType: 2, locationName: 'A-02', unitLoadLabel: 'LPN2' }),
      u({ itemDataId: 11, amount: 5, reservedAmount: 0, availableAmount: 5 }),
    ]);
    const s: ItemStock = map.get(10)!;
    expect(s.onHand).toBe(150);
    expect(s.available).toBe(130);
    expect(s.reserved).toBe(20);
    expect(s.held).toBe(50); // the lockType=2 unit
    expect(s.locations).toHaveLength(2);
    expect(map.get(11)!.onHand).toBe(5);
  });
});

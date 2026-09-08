import { describe, it, expect } from 'vitest';
import { buildItemView } from '../item-model';
import type { PackagingUnitResponse, ProductResponse } from '@/types/product';

const product = { id: 10, number: 'SKU-10', name: 'Widget', tradeGroup: 'Hardware',
  itemUnit: { id: 1, name: 'EA', unitType: 'PIECE' }, weight: 1.2, width: 10, depth: 5, height: 3,
  lotMandatory: false, bestBeforeMandatory: false, numbers: [], packagingUnits: [], state: 0 } as unknown as ProductResponse;

/** Real backend PackagingUnitResponse shape (no deprecated unitType/baseFactor). */
const pu = (over: Partial<PackagingUnitResponse>): PackagingUnitResponse => ({
  id: 1, name: 'Carton', amount: 12, itemUnitName: 'EA',
  weight: null, height: null, width: null, depth: null, packingLevel: 0, ...over,
});

const withUnits = (units: PackagingUnitResponse[]): ProductResponse =>
  ({ ...product, packagingUnits: units }) as ProductResponse;

describe('buildItemView', () => {
  it('carries real identity and null analytics when none supplied', () => {
    const v = buildItemView(product, undefined, undefined, undefined);
    expect(v.sku).toBe('SKU-10');
    expect(v.stock).toBeNull();
    expect(v.forecast).toBeNull();
    expect(v.slotting).toBeNull();
  });

  it('attaches stock + forecast + slotting when present', () => {
    const v = buildItemView(
      product,
      { onHand: 100, available: 80, reserved: 20, held: 0, locations: [] },
      { sku: 'SKU-10', avgDailyDemand: 4, forecastNextNDays: 120, currentOnHand: 100, suggestedReorderPoint: 40, suggestedReorderQty: 60, belowReorderPoint: false, confidence: 'HIGH' },
      { sku: 'SKU-10', abcClass: 'A', velocityRank: 3, currentLocation: 'A-01', currentOrderIndex: 5, direction: 'PROMOTE', reason: 'fast mover far from dock', severity: 0.7 },
    );
    expect(v.stock?.available).toBe(80);
    expect(v.forecast?.suggestedReorderPoint).toBe(40);
    expect(v.slotting?.abcClass).toBe('A');
  });
});

describe('buildItemView pack', () => {
  it('prefers the lowest packingLevel > 0 unit over amount-only units', () => {
    const v = buildItemView(withUnits([
      pu({ id: 1, name: 'Bulk Bag', amount: 500, packingLevel: 0 }),
      pu({ id: 2, name: 'Pallet', amount: 288, packingLevel: 2 }),
      pu({ id: 3, name: 'Carton', amount: 12, packingLevel: 1 }),
    ]));
    expect(v.pack).toBe('12 × Carton');
  });

  it('falls back to the first amount > 1 unit when no packingLevel > 0 exists', () => {
    const v = buildItemView(withUnits([
      pu({ id: 1, name: 'Each', amount: 1, packingLevel: 0 }),
      pu({ id: 2, name: 'Sixpack', amount: 6, packingLevel: 0 }),
    ]));
    expect(v.pack).toBe('6 × Sixpack');
  });

  it('renders — for an empty packagingUnits list', () => {
    expect(buildItemView(withUnits([])).pack).toBe('—');
  });

  it('renders — when nothing qualifies (all amount <= 1, no packingLevel > 0)', () => {
    expect(buildItemView(withUnits([pu({ name: 'Each', amount: 1, packingLevel: 0 })])).pack).toBe('—');
  });
});

describe('buildItemView cls', () => {
  it('is null without slotting, and the class letter with it', () => {
    expect(buildItemView(product, undefined, undefined, undefined).cls).toBeNull();
    const v = buildItemView(product, undefined, undefined, { sku: 'SKU-10', abcClass: 'A', velocityRank: 1, currentLocation: 'x', currentOrderIndex: 1, direction: 'PROMOTE', reason: '', severity: 1 });
    expect(v.cls).toBe('A');
  });
});

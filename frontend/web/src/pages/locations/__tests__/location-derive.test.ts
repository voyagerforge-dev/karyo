import { describe, it, expect } from 'vitest';
import { deriveLocation } from '../location-derive';
import type { LocationResponse } from '@/types/location';

const lt = { id: 1, name: 'Pick face', height: 60, width: 120, depth: 80, liftingCapacity: 250, created: '', modified: '' };
const area = { id: 1, name: 'Area', usages: [], created: '', modified: '' };

function loc(over: Partial<LocationResponse>): LocationResponse {
  return {
    id: 1, name: 'A4-B12', scanCode: 'A4·B12', locationType: lt, area, zone: null,
    locationCluster: null, allocation: 0, lockType: 0, lockTypeName: 'None',
    orderIndex: 412, xPos: 0, yPos: 0, zPos: 0, rack: 'A4', field: 'B12', section: null,
    created: '', modified: '',
    capacity: null, temperatureZone: null, handlingClass: null, kind: null, lastCountedAt: null,
    isClearing: false, plcCode: null, allocationState: 0,
    ...over,
  } as LocationResponse;
}

describe('deriveLocation (real fields only, no fabrication)', () => {
  it('takes occPct from allocation and real dimensions/weight from locationType', () => {
    const v = deriveLocation(loc({ allocation: 78 as unknown as LocationResponse['allocation'] }));
    expect(v.occPct).toBe(78);
    expect(v.status).toBe('Active');
    expect(v.dimensions).toBe('120×80×60');
    expect(v.maxWeight).toBe('250 kg');
    expect(v.pickSequence).toBe(412);
  });

  it('marks a zero-allocation bin Empty and a locked bin Blocked', () => {
    expect(deriveLocation(loc({ allocation: 0 as unknown as LocationResponse['allocation'] })).status).toBe('Empty');
    expect(deriveLocation(loc({ lockType: 2, lockTypeName: 'Quarantine' })).status).toBe('Blocked');
  });

  it('honest-gaps the un-modeled constraints with an em dash', () => {
    const v = deriveLocation(loc({}));
    expect(v.temperature).toBe('—');
    expect(v.storageClass).toBe('—');
    expect(v.replenRule).toBe('—');
    expect(v.lastCounted).toBe('—');
  });

  it('emits a note only for a blocked bin (no fabricated top-off note)', () => {
    expect(deriveLocation(loc({ allocation: 20 as unknown as LocationResponse['allocation'] })).note).toBeNull();
    expect(deriveLocation(loc({ lockType: 3, lockTypeName: 'Quality Fault' })).note?.tone).toBe('red');
  });

  it('flows the real Phase B metadata through when the backend sets it', () => {
    const v = deriveLocation(
      loc({
        capacity: 6,
        temperatureZone: 'CHILLED',
        handlingClass: 'HIGH_VALUE',
        kind: 'RESERVE',
        lastCountedAt: '2026-06-01T12:00:00Z',
      }),
    );
    expect(v.capacity).toBe(6);
    expect(v.temperature).toBe('Chilled');
    expect(v.storageClass).toBe('High Value');
    expect(v.kind).toBe('Reserve');
    expect(v.lastCounted).not.toBe('—');
    // replenRule stays an honest gap — genuinely not modeled by the layout API.
    expect(v.replenRule).toBe('—');
  });

  it('maps every backend kind to its display label and falls back to the type-name heuristic when null', () => {
    expect(deriveLocation(loc({ kind: 'PICK_FACE' })).kind).toBe('Pick face');
    expect(deriveLocation(loc({ kind: 'STAGING' })).kind).toBe('Staging');
    expect(deriveLocation(loc({ kind: 'BULK' })).kind).toBe('Bulk');
    expect(deriveLocation(loc({ kind: null, locationType: { ...lt, name: 'Pick Face Rack' } })).kind).toBe('Pick face');
  });

  it('honest-gaps capacity/temperature/storage-class/last-counted when the seeder has not set them', () => {
    const v = deriveLocation(loc({}));
    expect(v.capacity).toBeNull();
    expect(v.temperature).toBe('—');
    expect(v.storageClass).toBe('—');
    expect(v.lastCounted).toBe('—');
  });

  it('derives plcCode with an honest "—" gap when unset, real value when set', () => {
    expect(deriveLocation(loc({})).plcCode).toBe('—');
    expect(deriveLocation(loc({ plcCode: 'PLC-42' })).plcCode).toBe('PLC-42');
  });

  it('derives excludedFromPutaway from allocationState !== 0', () => {
    expect(deriveLocation(loc({ allocationState: 0 })).excludedFromPutaway).toBe(false);
    expect(deriveLocation(loc({ allocationState: 1 })).excludedFromPutaway).toBe(true);
  });

  it('derives isClearing straight through from the backend flag', () => {
    expect(deriveLocation(loc({ isClearing: false })).isClearing).toBe(false);
    expect(deriveLocation(loc({ isClearing: true })).isClearing).toBe(true);
  });
});

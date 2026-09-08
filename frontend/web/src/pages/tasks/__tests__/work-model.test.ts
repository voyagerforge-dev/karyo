import { describe, expect, it } from 'vitest';
import { matchesWorkFilter, mergeWork, transportOrderToWorkItem, WORK_TYPE_META } from '../work-model';
import { refSourceId, refType } from '@/types/work';
import type { TransportOrderResponse } from '@/types/tasks';

describe('work model', () => {
  it('parses refs', () => {
    expect(refSourceId('PICK:42')).toBe(42);
    expect(refType('COUNT:7')).toBe('COUNT');
  });
  it('meta covers all eight types', () => {
    expect(Object.keys(WORK_TYPE_META).sort()).toEqual([
      'COUNT', 'CROSS_DOCK', 'MOVE', 'PICK', 'PUTAWAY', 'RECEIVE', 'REPLENISH', 'TRANSFER',
    ]);
  });
  it('filter partitions by type', () => {
    expect(matchesWorkFilter({ workType: 'PICK' }, 'PICK')).toBe(true);
    expect(matchesWorkFilter({ workType: 'MOVE' }, 'PICK')).toBe(false);
    expect(matchesWorkFilter({ workType: 'MOVE' }, 'all')).toBe(true);
  });
  it('merge puts mine first and dedupes by ref', () => {
    const mine = [{ ref: 'PICK:1', workType: 'PICK' } as never];
    const avail = [{ ref: 'PICK:1' } as never, { ref: 'MOVE:2', workType: 'MOVE' } as never];
    const merged = mergeWork(avail, mine);
    expect(merged.map((w: { ref: string }) => w.ref)).toEqual(['PICK:1', 'MOVE:2']);
  });

  // Row 22: the paused-transport list is fetched raw and rendered through the
  // same WorkRow/TransportPane plumbing as the work inbox -- this synthesis is
  // what bridges the two shapes.
  it('transportOrderToWorkItem synthesizes a ref-compatible WorkItemResponse', () => {
    const order: TransportOrderResponse = {
      id: 99,
      orderNumber: 'TO-2099',
      transportType: 'PUTAWAY',
      unitLoadId: 41,
      unitLoadLabel: 'UL-PAUSED',
      sourceLocationId: 5,
      sourceLocationName: 'DOCK-1',
      destinationLocationId: null,
      destinationLocationName: null,
      suggestedLocationId: 10,
      suggestedLocationName: 'A-01-01',
      state: 400,
      stateName: 'RESERVED',
      prio: 50,
      operatorId: 'op-alice',
      executorType: 'HUMAN',
      note: null,
      goodsReceiptLineId: null,
      clientId: 1,
      created: '2026-08-14T00:00:00Z',
      modified: '2026-08-14T00:00:00Z',
      pausedAt: '2026-08-14T00:00:00Z',
      started: null,
      finished: null,
      successorId: null,
      externalNumber: null,
      externalId: null,
      itemDataId: null,
      itemDataNumber: null,
      lotNumber: null,
      amount: null,
      confirmedAmount: null,
      sourceStockUnitId: null,
    };

    expect(transportOrderToWorkItem(order)).toEqual({
      ref: 'PUTAWAY:99',
      workType: 'PUTAWAY',
      priority: 50,
      state: 'CLAIMED',
      claimedBy: 'op-alice',
      zone: null,
      primaryLocation: 'DOCK-1',
      destination: 'A-01-01',
      summary: 'TO-2099 · UL-PAUSED',
      createdAt: '2026-08-14T00:00:00Z',
    });

    expect(transportOrderToWorkItem({ ...order, operatorId: null }).state).toBe('OPEN');
  });
});

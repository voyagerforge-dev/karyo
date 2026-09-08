import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import type { FixAssignmentResponse } from '@/types/location';
import {
  moveUnitLoads,
  toReorderPointMap,
  useAdjustStock,
  useFixAssignments,
  useSetStockLock,
  useTransferUnitLoad,
} from '../use-inventory';
import { api } from '@/lib/api-client';

vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return createElement(QueryClientProvider, { client: qc }, children);
}

beforeEach(() => vi.clearAllMocks());

describe('use-inventory mutation hooks', () => {
  it('useAdjustStock posts the absolute newAmount with a manual-adjust activity code', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, amount: 42 });
    const { result } = renderHook(() => useAdjustStock(), { wrapper });
    act(() => result.current.mutate({ id: 9, newAmount: 42 }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/stock-units/9/adjust', {
        newAmount: 42,
        activityCode: 'MANUAL_ADJUST',
      }),
    );
  });

  it('useSetStockLock places a hold (lockType 103) with the given reason', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 9, lockType: 103 });
    const { result } = renderHook(() => useSetStockLock(), { wrapper });
    act(() => result.current.mutate({ id: 9, lockType: 103, reason: 'suspected damage' }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/stock-units/9/lock', {
        lockType: 103,
        reason: 'suspected damage',
      }),
    );
  });

  it('useSetStockLock releases a hold (lockType 0)', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 9, lockType: 0 });
    const { result } = renderHook(() => useSetStockLock(), { wrapper });
    act(() => result.current.mutate({ id: 9, lockType: 0 }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/stock-units/9/lock', {
        lockType: 0,
        reason: undefined,
      }),
    );
  });

  it('useTransferUnitLoad posts the destination with a manual-move activity code', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 100, storageLocationId: 5 });
    const { result } = renderHook(() => useTransferUnitLoad(), { wrapper });
    act(() =>
      result.current.mutate({
        id: 100,
        destinationLocationId: 5,
        destinationLocationName: 'C1-B02',
      }),
    );
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/unit-loads/100/transfer', {
        destinationLocationId: 5,
        destinationLocationName: 'C1-B02',
        activityCode: 'MANUAL_MOVE',
      }),
    );
  });

  it('moveUnitLoads fires one transfer per distinct unit-load id, deduping repeats', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 0 });
    const { result } = renderHook(() => useTransferUnitLoad(), { wrapper });

    // Duplicate id 200 (e.g. two stock units on the same unit load) must
    // still collapse to a single transfer call.
    await act(() =>
      moveUnitLoads(
        [200, 201, 200],
        { id: 7, name: 'C1-B02' },
        result.current.mutateAsync,
      ),
    );

    expect(api.post).toHaveBeenCalledTimes(2);
    expect(api.post).toHaveBeenCalledWith('/api/v1/unit-loads/200/transfer', {
      destinationLocationId: 7,
      destinationLocationName: 'C1-B02',
      activityCode: 'MANUAL_MOVE',
    });
    expect(api.post).toHaveBeenCalledWith('/api/v1/unit-loads/201/transfer', {
      destinationLocationId: 7,
      destinationLocationName: 'C1-B02',
      activityCode: 'MANUAL_MOVE',
    });
  });
});

describe('useFixAssignments', () => {
  it('fetches the non-paginated fix-assignments list', async () => {
    vi.mocked(api.get).mockResolvedValue([]);
    const { result } = renderHook(() => useFixAssignments(), { wrapper });
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/fix-assignments'));
    expect(result.current).toBeDefined();
  });
});

describe('toReorderPointMap', () => {
  const fa = (o: Partial<FixAssignmentResponse>): FixAssignmentResponse => ({
    id: 1, locationId: 1, locationName: 'A-01-01', itemDataId: 10, itemDataNumber: 'SKU-1',
    minAmount: null, maxAmount: null, desiredAmount: null, currentStockAmount: null,
    orderIndex: 0, created: '', modified: '', ...o,
  });

  it('keys by `${itemDataId}@${locationId}` and drops entries with a null minAmount', () => {
    const map = toReorderPointMap([
      fa({ itemDataId: 10, locationId: 1, minAmount: 20 }),
      fa({ itemDataId: 11, locationId: 2, minAmount: null }),
    ]);
    expect(map.get('10@1')).toBe(20);
    expect(map.has('11@2')).toBe(false);
  });
});

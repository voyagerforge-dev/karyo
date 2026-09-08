import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import {
  useBulkConfirm,
  useBulkLines,
  useCancelPickOrder,
  useConfirmPick,
  useExtinguishStock,
} from '../use-pick-orders';
import type { PickOrderResponse, PickResponse } from '@/types/pick-orders';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class extends Error {},
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}));

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: qc }, children);
};

const confirmedPick: PickResponse = {
  id: 1,
  deliveryOrderLineId: 5,
  itemDataId: 9,
  itemDataNumber: 'SKU-1',
  sourceStockUnitId: 3,
  plannedAmount: 60,
  pickedAmount: 60,
  state: 600,
  lotNumber: null,
  pickingType: 'COMPLETE',
  followUpForPickId: null,
  substitutedItemDataId: null,
  pickedLotNumber: null,
  pickedBestBefore: null,
};

const canceledOrder: PickOrderResponse = {
  id: 42,
  pickOrderNumber: 'PO-42',
  deliveryOrderId: 9,
  deliveryOrderNumber: 'DO-9',
  state: 800,
  targetUnitLoadId: null,
  picks: [],
  weight: null,
  volume: null,
  destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
};

const extinguishOrder: PickOrderResponse = {
  id: 43,
  pickOrderNumber: 'EXT-43',
  deliveryOrderId: null,
  deliveryOrderNumber: null,
  state: 100,
  targetUnitLoadId: null,
  picks: [],
  weight: null,
  volume: null,
  destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
};

describe('useConfirmPick', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/picks/{id}/confirm', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(confirmedPick);
    const { result } = renderHook(() => useConfirmPick(), { wrapper });
    result.current.mutate({ pickId: 1, body: { pickedAmount: 60 } });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/picks/1/confirm', { pickedAmount: 60 });
  });

  // I2 (final review): confirming the FINAL pick removes the PickOrder from
  // both the claimable and claimed-by pools (PickOrderRepository.findClaimable
  // /findClaimedBy filter on RELEASED/STARTED), so the unified work inbox
  // (['work', ...] queries) must be invalidated alongside pick-orders/orders --
  // otherwise the row stays visible in /tasks and re-claiming it 409s.
  it('invalidates pick-orders, orders AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(confirmedPick);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useConfirmPick(), { wrapper: qcWrapper });
    result.current.mutate({ pickId: 1, body: { pickedAmount: 60 } });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['pick-orders']);
    expect(invalidatedKeys).toContainEqual(['orders']);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useCancelPickOrder', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/pick-orders/{id}/cancel with no body', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(canceledOrder);
    const { result } = renderHook(() => useCancelPickOrder(), { wrapper });
    result.current.mutate(42);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/pick-orders/42/cancel', {});
  });

  // Same work-pool-consistency requirement as useConfirmPick: a canceled
  // order must also vanish from findClaimable/findClaimedBy.
  it('invalidates pick-orders, orders AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(canceledOrder);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useCancelPickOrder(), { wrapper: qcWrapper });
    result.current.mutate(42);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['pick-orders']);
    expect(invalidatedKeys).toContainEqual(['orders']);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useExtinguishStock', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs the request body to /api/v1/pick-orders/extinguish', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(extinguishOrder);
    const { result } = renderHook(() => useExtinguishStock(), { wrapper });
    result.current.mutate({ unitLoadId: 41 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/pick-orders/extinguish', { unitLoadId: 41 });
  });

  it('invalidates pick-orders, orders AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(extinguishOrder);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useExtinguishStock(), { wrapper: qcWrapper });
    result.current.mutate({ unitLoadId: 41 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['pick-orders']);
    expect(invalidatedKeys).toContainEqual(['orders']);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('bulk pick hooks (Sprint B)', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.post).mockReset();
  });

  it('useBulkLines fetches the aggregated lines of a bulk pick order', async () => {
    const line = {
      sourceStockUnitId: 3,
      locationName: 'A-01-01',
      unitLoadLabel: 'UL-1',
      itemDataId: 5,
      itemDataNumber: 'SKU-1',
      lotNumber: null,
      plannedTotal: 40,
      pickedTotal: 0,
      openSlices: 3,
    };
    vi.mocked(api.get).mockResolvedValue([line]);
    const { result } = renderHook(() => useBulkLines(7), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual([line]));
    expect(api.get).toHaveBeenCalledWith('/api/v1/pick-orders/7/bulk-lines');
  });

  it('useBulkLines is disabled for non-bulk orders', () => {
    const { result } = renderHook(() => useBulkLines(7, false), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('useBulkConfirm posts the fan-out confirm', async () => {
    const out = {
      pickOrderId: 7,
      sourceStockUnitId: 3,
      pickedAmount: 27,
      filledSlices: 2,
      shortSlices: 1,
      slices: [],
    };
    vi.mocked(api.post).mockResolvedValue(out);
    const { result } = renderHook(() => useBulkConfirm(), { wrapper });
    await result.current.mutateAsync({
      pickOrderId: 7,
      body: { sourceStockUnitId: 3, pickedAmount: 27 },
    });
    expect(api.post).toHaveBeenCalledWith('/api/v1/pick-orders/7/bulk-confirm', {
      sourceStockUnitId: 3,
      pickedAmount: 27,
    });
  });
});

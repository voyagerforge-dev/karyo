import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import { useCancelOrder } from '../use-orders';
import type { DeliveryOrderResponse } from '@/types/orders';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
  ApiError: class extends Error {},
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}));

const canceledOrder = {
  id: 7,
  orderNumber: 'DO-7',
  state: 800,
  lines: [],
} as unknown as DeliveryOrderResponse;

describe('useCancelOrder', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/delivery-orders/{id}/cancel with no body', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(canceledOrder);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useCancelOrder(), { wrapper });
    result.current.mutate(7);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith('/api/v1/delivery-orders/7/cancel', {});
  });

  // Task 6: the backend cancel now cascades a force-finish into fulfillment (PickCancelPort),
  // so the order's PickOrder leaves the claimable/claimed work pools. Without ['work'] and
  // ['pick-orders'] invalidation the canceled pick order lingers in /tasks and /picking.
  it('invalidates orders, work AND pick-orders on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(canceledOrder);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const wrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useCancelOrder(), { wrapper });
    result.current.mutate(7);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['orders']);
    expect(invalidatedKeys).toContainEqual(['work']);
    expect(invalidatedKeys).toContainEqual(['pick-orders']);
  });
});

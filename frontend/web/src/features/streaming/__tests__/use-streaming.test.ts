import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { useStreamingStatus, useStreamingOrders, useRetryStreamingOrder } from '../use-streaming';
import { api } from '@/lib/api-client';
import type { StreamBucket } from '@/types/streaming';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
}));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return createElement(QueryClientProvider, { client: qc }, children);
}

function spiedWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
  const qcWrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
  return { invalidateSpy, qcWrapper };
}

beforeEach(() => vi.clearAllMocks());

describe('useStreamingStatus', () => {
  it('fetches the streaming status', async () => {
    vi.mocked(api.get).mockResolvedValue({ enabled: true, strategies: [] });
    renderHook(() => useStreamingStatus(), { wrapper });
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/streaming/status'));
  });
});

describe('useStreamingOrders', () => {
  it('fetches orders for the given bucket with the default size', async () => {
    vi.mocked(api.get).mockResolvedValue([]);
    renderHook(() => useStreamingOrders('STALLED'), { wrapper });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/api/v1/streaming/orders?bucket=STALLED&size=100'),
    );
  });

  it('refetches when the bucket changes', async () => {
    vi.mocked(api.get).mockResolvedValue([]);
    const { rerender } = renderHook(
      ({ bucket }: { bucket: StreamBucket }) => useStreamingOrders(bucket),
      { wrapper, initialProps: { bucket: 'WAITING' } },
    );
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/api/v1/streaming/orders?bucket=WAITING&size=100'),
    );
    rerender({ bucket: 'ESCALATED' });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/api/v1/streaming/orders?bucket=ESCALATED&size=100'),
    );
  });
});

describe('useRetryStreamingOrder', () => {
  it('posts to the retry endpoint and invalidates streaming + orders queries', async () => {
    vi.mocked(api.post).mockResolvedValue({ orderId: 5, orderNumber: 'DO-5' });
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useRetryStreamingOrder(), { wrapper: qcWrapper });

    result.current.mutate(5);

    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/streaming/orders/5/retry', {}));
    await waitFor(() =>
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['streaming'] }),
    );
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['orders'] });
  });
});

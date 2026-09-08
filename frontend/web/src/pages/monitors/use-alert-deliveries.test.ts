import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useAlertDeliveries, useRedeliverAlertDelivery } = await import(
  '@/pages/monitors/use-alert-deliveries'
);

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const DELIVERY_DTO = {
  id: 7,
  alertId: 42,
  channelKey: 'email',
  status: 'DEAD',
  attempts: 8,
  nextAttemptAt: new Date().toISOString(),
  lastError: 'no email recipients configured',
  created: new Date().toISOString(),
};

describe('useAlertDeliveries', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockResolvedValue([DELIVERY_DTO]);
  });

  it('fetches all deliveries with no status filter', async () => {
    const { result } = renderHook(() => useAlertDeliveries(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.data).toHaveLength(1));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/alert-deliveries');
    expect(result.current.data?.[0]).toMatchObject({ id: 7, channelKey: 'email', status: 'DEAD' });
  });

  it('fetches deliveries filtered by status', async () => {
    const { result } = renderHook(() => useAlertDeliveries('DEAD'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.data).toHaveLength(1));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/alert-deliveries?status=DEAD');
  });
});

describe('useRedeliverAlertDelivery', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.post.mockResolvedValue(undefined);
  });

  it('POSTs redeliver for the given id', async () => {
    const { result } = renderHook(() => useRedeliverAlertDelivery(), { wrapper: wrapper() });

    await act(async () => {
      result.current.mutate(7);
    });

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/alert-deliveries/7/redeliver', {});
  });
});

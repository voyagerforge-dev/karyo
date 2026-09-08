import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useSubscriptions } = await import('@/features/webhooks/use-webhooks');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useSubscriptions', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches subscriptions from the API', async () => {
    mockApi.get.mockResolvedValue([{ id: 1, name: 'a', targetUrl: 'https://x', eventTypes: ['*'], active: true }]);
    const { result } = renderHook(() => useSubscriptions(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/webhook-subscriptions');
    expect(result.current.data?.[0].name).toBe('a');
  });
});

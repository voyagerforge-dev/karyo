import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useKpis } = await import('@/features/insights/use-kpis');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useKpis', () => {
  beforeEach(() => vi.clearAllMocks());
  it('fetches the kpis endpoint for the given range', async () => {
    mockApi.get.mockResolvedValue({ range: '7D', rangeLabel: 'Last 7 days', tiles: [], chart: { outbound: [], received: [] } });
    const { result } = renderHook(() => useKpis('7D'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/insights/kpis?range=7D');
  });
});

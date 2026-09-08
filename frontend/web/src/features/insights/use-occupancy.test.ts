import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useOccupancy } = await import('@/features/insights/use-occupancy');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useOccupancy', () => {
  beforeEach(() => vi.clearAllMocks());
  it('fetches the occupancy endpoint', async () => {
    mockApi.get.mockResolvedValue({ zones: [], unzoned: null, totals: { occupied: 0, total: 0, pct: 0 } });
    const { result } = renderHook(() => useOccupancy(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/insights/occupancy');
  });
});

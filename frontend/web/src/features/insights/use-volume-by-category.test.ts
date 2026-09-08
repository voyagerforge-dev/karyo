import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useVolumeByCategory } = await import('@/features/insights/use-volume-by-category');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useVolumeByCategory', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches the volume-by-category endpoint for the given range', async () => {
    mockApi.get.mockResolvedValue([{ category: 'Electronics', volume: 80, lineCount: 10 }]);
    const { result } = renderHook(() => useVolumeByCategory('7D'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/insights/volume-by-category?range=7D');
    expect(result.current.data).toEqual([{ category: 'Electronics', volume: 80, lineCount: 10 }]);
  });
});

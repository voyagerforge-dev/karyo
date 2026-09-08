import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useExtensions } = await import('@/pages/admin/use-extensions');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const EXTENSION: import('@/pages/admin/use-extensions').ExtensionInfo = {
  spiInterface: 'ProductLookup',
  spiFqn: 'com.karyo.product.spi.ProductLookup',
  module: 'karyo-product',
  implementations: ['DefaultProductLookup'],
  implementationCount: 1,
};

describe('useExtensions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockResolvedValue([EXTENSION]);
  });

  it('GETs /api/v1/admin/extensions and returns the rows', async () => {
    const { result } = renderHook(() => useExtensions(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.data).toHaveLength(1));

    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/admin/extensions');
    expect(result.current.data?.[0]).toMatchObject(EXTENSION);
  });

  it('surfaces an error when the caller is not entitled (e.g. 403)', async () => {
    mockApi.get.mockReset();
    mockApi.get.mockRejectedValue(new Error('403 Forbidden'));

    const { result } = renderHook(() => useExtensions(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

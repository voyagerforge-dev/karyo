import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useLicense } = await import('@/features/license/use-license');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useLicense', () => {
  beforeEach(() => vi.clearAllMocks());

  it('parses entitlements and exposes isEntitled', async () => {
    const body = { edition: 'commercial', entitlements: ['monitors', 'forecasting'] };
    mockApi.get.mockResolvedValue(body);
    const { result } = renderHook(() => useLicense(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.data).toBeDefined());

    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/license');
    expect(result.current.data).toEqual(body);
    expect(result.current.isEntitled('monitors')).toBe(true);
    expect(result.current.isEntitled('slotting')).toBe(false);
  });

  it('isEntitled returns false before data has loaded', () => {
    mockApi.get.mockReturnValue(new Promise(() => {}));
    const { result } = renderHook(() => useLicense(), { wrapper: wrapper() });

    expect(result.current.isLoading).toBe(true);
    expect(result.current.isEntitled('monitors')).toBe(false);
  });
});

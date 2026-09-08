import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useSlotting } = await import('@/pages/insights/use-slotting');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const RESLOT_DTO = {
  sku: 'SKU-1',
  abcClass: 'A',
  velocityRank: 3,
  currentLocation: 'A-01-01',
  currentOrderIndex: 42,
  direction: 'PROMOTE',
  reason: 'High velocity SKU stored far from pack area',
  severity: 8,
};

describe('useSlotting', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockResolvedValue([RESLOT_DTO]);
  });

  it('GETs /api/v1/slotting/recommendations and returns the rows', async () => {
    const { result } = renderHook(() => useSlotting(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.data).toHaveLength(1));

    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/slotting/recommendations');
    expect(result.current.data?.[0]).toMatchObject(RESLOT_DTO);
  });
});

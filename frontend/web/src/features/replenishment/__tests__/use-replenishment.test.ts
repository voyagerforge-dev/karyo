import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import { useReplenishmentNeeds, useScanReplenishment } from '../use-replenishment';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class extends Error {},
}));

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: qc }, children);
};

describe('useReplenishmentNeeds', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches the needs list from /api/v1/replenishment/needs', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      { fixAssignmentId: 1, locationName: 'A-01-01', belowMin: true, hasOpenTask: false },
    ]);
    const { result } = renderHook(() => useReplenishmentNeeds(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/replenishment/needs');
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].fixAssignmentId).toBe(1);
  });
});

describe('useScanReplenishment', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/replenishment/scan with an empty body', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      generated: [{ taskId: 10, orderNumber: 'TO-1', fixAssignmentId: 1, locationName: 'A-01-01', itemDataNumber: 'SKU-1', unitLoadId: 5 }],
      shortfalls: [],
    });
    const { result } = renderHook(() => useScanReplenishment(), { wrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/replenishment/scan', {});
    expect(result.current.data?.generated).toHaveLength(1);
  });

  // I3 (final review): the scan button lives inside /tasks now, and generated
  // REPLENISH transport orders must appear in the work inbox (['work', ...]
  // queries) without a remount -- invalidating only ['transport-orders'] left
  // the /tasks list stale.
  it('invalidates replenishment-needs, transport-orders AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ generated: [], shortfalls: [] });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useScanReplenishment(), { wrapper: qcWrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['replenishment-needs']);
    expect(invalidatedKeys).toContainEqual(['transport-orders']);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

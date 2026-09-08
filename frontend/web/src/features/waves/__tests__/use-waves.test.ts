import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import {
  useWaves,
  useWave,
  useWaveProgress,
  useCreateWave,
  useReleaseWave,
  useCancelWave,
  useMarkGroupReady,
  useSelectionRuleFields,
  useSelectionRules,
  useCreateSelectionRule,
  useUpdateSelectionRule,
  useDeleteSelectionRule,
  usePreviewSelectionRule,
} from '../use-waves';
import { api } from '@/lib/api-client';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
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

describe('useWaves', () => {
  it('fetches the wave list with paging params and no state filter by default', async () => {
    vi.mocked(api.get).mockResolvedValue({ content: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 0 } });
    renderHook(() => useWaves(), { wrapper });
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/waves?page=0&size=50'));
  });

  it('adds the state NAME (not a numeric code) as a query param when filtering', async () => {
    vi.mocked(api.get).mockResolvedValue({ content: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 0 } });
    renderHook(() => useWaves('PICKING'), { wrapper });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith('/api/v1/waves?page=0&size=50&state=PICKING'),
    );
  });
});

describe('useWave', () => {
  it('fetches wave detail by id', async () => {
    vi.mocked(api.get).mockResolvedValue({
      wave: { id: 1, waveNumber: 'W-1' },
      orders: [],
      shortages: [],
      groups: [],
    });
    renderHook(() => useWave(1), { wrapper });
    await waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/v1/waves/1'));
  });

  it('does not fetch when id is undefined', () => {
    renderHook(() => useWave(undefined), { wrapper });
    expect(api.get).not.toHaveBeenCalled();
  });
});

describe('useWaveProgress -- polling gate (refetchInterval: 15_000 only for 300-500)', () => {
  it.each([
    ['RELEASED' as const, true],
    ['PICKING' as const, true],
    ['CONSOLIDATING' as const, true],
    ['PLANNED' as const, false],
    ['COMPLETED' as const, false],
    ['CANCELLED' as const, false],
  ])('state=%s -> refetches again after 15s: %s', async (state, shouldRefetch) => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(api.get).mockResolvedValue({ waveId: 1, state });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const qcWrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: qc }, children);

    renderHook(() => useWaveProgress(1, state), { wrapper: qcWrapper });
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_001);
    });

    expect(api.get).toHaveBeenCalledTimes(shouldRefetch ? 2 : 1);
    vi.useRealTimers();
  });

  it('does not fetch when id is undefined', () => {
    renderHook(() => useWaveProgress(undefined, 'PICKING'), { wrapper });
    expect(api.get).not.toHaveBeenCalled();
  });
});

describe('use-waves mutations', () => {
  it('useCreateWave posts the create body', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, waveNumber: 'W-1' });
    const { result } = renderHook(() => useCreateWave(), { wrapper });
    act(() => result.current.mutate({ orderStrategyId: 5 }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/waves', { orderStrategyId: 5 }));
  });

  it('useCreateWave invalidates the waves list on success', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, waveNumber: 'W-1' });
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useCreateWave(), { wrapper: qcWrapper });
    act(() => result.current.mutate({ orderStrategyId: 5 }));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['waves', 'list']);
  });

  it('useReleaseWave posts to the release endpoint and invalidates the wave by id', async () => {
    vi.mocked(api.post).mockResolvedValue({
      wave: { id: 7, waveNumber: 'W-7' },
      orders: [],
      shortages: [],
      groups: [],
    });
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useReleaseWave(), { wrapper: qcWrapper });
    act(() => result.current.mutate(7));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/waves/7/release', {}));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['waves', 'list']);
    expect(invalidatedKeys).toContainEqual(['waves', 7]);
    expect(invalidatedKeys).toContainEqual(['orders']);
  });

  it('useCancelWave posts to the cancel endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 3, waveNumber: 'W-3', state: 'CANCELLED' });
    const { result } = renderHook(() => useCancelWave(), { wrapper });
    act(() => result.current.mutate(3));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/waves/3/cancel', {}));
  });

  it('useMarkGroupReady posts to the consolidation-group ready endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 9, destinationKey: 'Z1', state: 'READY' });
    const { result } = renderHook(() => useMarkGroupReady(), { wrapper });
    act(() => result.current.mutate({ waveId: 7, groupId: 9 }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/waves/7/consolidation-groups/9/ready', {}),
    );
  });
});

describe('selection rules (selection-rules sprint, Task 5)', () => {
  const RULE = {
    id: 1,
    name: 'Rush orders',
    description: null,
    definition: { combinator: 'AND', conditions: [{ field: 'prio', op: 'gte', value: 80 }], groups: [] },
    boundByStrategies: 0,
    created: '2026-08-21T00:00:00Z',
  };

  it('useSelectionRuleFields fetches the field registry', async () => {
    vi.mocked(api.get).mockResolvedValue([{ field: 'prio', type: 'NUMBER', label: 'Priority', ops: ['eq'] }]);
    const { result } = renderHook(() => useSelectionRuleFields(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/wave-selection-rules/fields');
  });

  it('useSelectionRules fetches the paginated rule list', async () => {
    vi.mocked(api.get).mockResolvedValue({
      content: [RULE],
      page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
    });
    const { result } = renderHook(() => useSelectionRules(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/wave-selection-rules?page=0&size=50');
  });

  it('useCreateSelectionRule posts the rule body and invalidates wave-rules on success', async () => {
    vi.mocked(api.post).mockResolvedValue(RULE);
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useCreateSelectionRule(), { wrapper: qcWrapper });
    const body = { name: 'Rush orders', definition: RULE.definition };
    act(() => result.current.mutate(body as never));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith('/api/v1/wave-selection-rules', body);
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['wave-rules']);
  });

  it('useUpdateSelectionRule puts the rule body by id and invalidates wave-rules on success', async () => {
    vi.mocked(api.put).mockResolvedValue(RULE);
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useUpdateSelectionRule(), { wrapper: qcWrapper });
    const body = { name: 'Rush orders', definition: RULE.definition };
    act(() => result.current.mutate({ id: 1, body } as never));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.put).toHaveBeenCalledWith('/api/v1/wave-selection-rules/1', body);
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['wave-rules']);
  });

  it('useDeleteSelectionRule deletes by id and invalidates wave-rules on success', async () => {
    vi.mocked(api.delete).mockResolvedValue(undefined);
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useDeleteSelectionRule(), { wrapper: qcWrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.delete).toHaveBeenCalledWith('/api/v1/wave-selection-rules/1');
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['wave-rules']);
  });

  it('usePreviewSelectionRule posts to the preview endpoint for a saved rule id', async () => {
    vi.mocked(api.post).mockResolvedValue({ matchedCount: 2, poolSize: 10, sample: [] });
    const { result } = renderHook(() => usePreviewSelectionRule(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/wave-selection-rules/1/preview', {}));
  });
});

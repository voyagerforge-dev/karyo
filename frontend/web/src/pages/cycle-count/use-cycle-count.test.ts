import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import {
  useSessions,
  useSession,
  useOrderSession,
  useCountOrder,
  useCountOrderEntry,
  useStartCount,
  useSubmitCount,
  useLocationEmpty,
  useAccept,
  useRecount,
  useCancelOrder,
  useCampaigns,
  useCampaign,
  useCreateCampaign,
  useCloseCampaign,
} from './use-cycle-count';
import type {
  CountSessionView,
  CountSessionSummaryView,
  CountOrderView,
  CountCampaignView,
  CountCampaignRollupView,
} from '@/types/cycle-count';
import type { PaginatedResponse } from '@/types/api';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class extends Error {},
}));

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: qc }, children);
};

/** Full graph -- returned by GET /count-sessions/{id} (useSession). */
const mockSession: CountSessionView = {
  id: 1,
  sessionNumber: 'CS-0001',
  type: 'CYCLE',
  state: 100,
  orders: [],
  campaignId: null,
};

/** Summary projection -- returned by GET /count-sessions (useSessions, paginated) and
 *  POST /count-sessions (useStartCount). */
const mockSessionSummary: CountSessionSummaryView = {
  id: 1,
  sessionNumber: 'CS-0001',
  type: 'CYCLE',
  state: 100,
  campaignId: null,
  orderCount: 0,
  countedCount: 0,
  finishedCount: 0,
};

const mockSessionsPage: PaginatedResponse<CountSessionSummaryView> = {
  content: [mockSessionSummary],
  page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
};

const mockCampaign: CountCampaignView = {
  id: 5,
  campaignNumber: 'CC-0001',
  name: 'Q3 sweep',
  type: 'CYCLE',
  state: 100,
  started: '2026-07-01T00:00:00Z',
  ended: null,
};

const mockCampaignRollup: CountCampaignRollupView = {
  ...mockCampaign,
  sessions: 2,
  ordersByState: { generated: 0, counted: 0, finished: 2, cancelled: 0 },
  discrepancyLines: 1,
};

const mockOrder: CountOrderView = {
  id: 10,
  orderNumber: 'CO-0001',
  sessionId: 1,
  locationId: 5,
  locationName: 'A-01-01',
  state: 50,
  lines: [],
};

describe('useSessions', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-sessions?page=0&size=100 and unwraps .content', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue(mockSessionsPage);
    const { result } = renderHook(() => useSessions(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-sessions?page=0&size=100');
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data?.[0].sessionNumber).toBe('CS-0001');
    // The summary projection carries no nested orders (defect-burndown row 8).
    expect(result.current.data?.[0]).not.toHaveProperty('orders');
  });
});

describe('useOrderSession', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-orders/:id and resolves to its sessionId', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue(mockOrder);
    const { result } = renderHook(() => useOrderSession(10), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-orders/10');
    expect(result.current.data).toBe(mockOrder.sessionId);
  });

  it('does not fetch when id is undefined', () => {
    const { result } = renderHook(() => useOrderSession(undefined), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(api.get).not.toHaveBeenCalled();
  });
});

describe('useSession', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-sessions/:id when id is provided', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue(mockSession);
    const { result } = renderHook(() => useSession(1), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-sessions/1');
    expect(result.current.data?.id).toBe(1);
  });

  it('does not fetch when id is undefined', () => {
    const { result } = renderHook(() => useSession(undefined), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(api.get).not.toHaveBeenCalled();
  });
});

describe('useCountOrder', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-orders/:id (review view)', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue(mockOrder);
    const { result } = renderHook(() => useCountOrder(10), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-orders/10');
  });
});

describe('useCountOrderEntry', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-orders/:id?view=entry for blind entry', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 10, lines: [] });
    const { result } = renderHook(() => useCountOrderEntry(10), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-orders/10?view=entry');
  });
});

describe('useStartCount', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-sessions', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(mockSessionSummary);
    const { result } = renderHook(() => useStartCount(), { wrapper });
    result.current.mutate({ areaId: 3, blindCount: true });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/count-sessions', {
      areaId: 3,
      blindCount: true,
    });
    expect(result.current.data?.sessionNumber).toBe('CS-0001');
  });

  // M-1 (final review): starting a session creates COUNT work items, which must
  // appear in the unified work inbox (['work', ...] queries) without a remount.
  it('invalidates count-sessions AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(mockSessionSummary);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useStartCount(), { wrapper: qcWrapper });
    result.current.mutate({ areaId: 3, blindCount: true });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useSubmitCount', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-orders/:id/count', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 500 });
    const { result } = renderHook(() => useSubmitCount(10), { wrapper });
    result.current.mutate({ lines: [{ lineId: 1, countedAmount: 5 }] });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/count-orders/10/count', {
      lines: [{ lineId: 1, countedAmount: 5 }],
    });
  });

  // M-1 (final review): a submitted count leaves the claimable/claimed COUNT pool
  // (50 -> 500) -- the work inbox (['work', ...] queries) must go stale too.
  it('invalidates count-sessions, session, order AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 500 });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useSubmitCount(10), { wrapper: qcWrapper });
    result.current.mutate({ lines: [{ lineId: 1, countedAmount: 5 }] });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    // mockOrder.sessionId is now a real backend-sent value (was previously undefined
    // FE-side, making this invalidation a silent no-op -- see StocktakingResourceTest
    // `count order review view carries the owning session id`).
    expect(invalidatedKeys).toContainEqual(['count-sessions', mockOrder.sessionId]);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useLocationEmpty', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-orders/:id/location-empty with empty body', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 700, lines: [] });
    const { result } = renderHook(() => useLocationEmpty(10), { wrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/count-orders/10/location-empty', {});
  });

  // St3: either branch (zero-line FINISHED(700), or has-lines COUNTED(500)) moves the order
  // out of GENERATED -- the session, this order, AND the work inbox all need a refresh, same
  // set as useAccept/useRecount.
  it('invalidates count-sessions, session, order AND work on success (zero-line -> FINISHED branch)', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 700, lines: [] });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useLocationEmpty(10), { wrapper: qcWrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    expect(invalidatedKeys).toContainEqual(['count-sessions', mockOrder.sessionId]);
    expect(invalidatedKeys).toContainEqual(['work']);
  });

  it('invalidates the same set on the has-lines -> COUNTED(500) branch', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 500 });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useLocationEmpty(10), { wrapper: qcWrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    expect(invalidatedKeys).toContainEqual(['count-sessions', mockOrder.sessionId]);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useAccept', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-orders/:id/accept with empty body', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 700 });
    const { result } = renderHook(() => useAccept(10), { wrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/count-orders/10/accept', {});
  });

  // M-1 (final review): accepting finishes the order (FINISHED(700)) -- the work
  // inbox (['work', ...] queries) must go stale too.
  it('invalidates count-sessions, session, order AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 700 });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useAccept(10), { wrapper: qcWrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    // mockOrder.sessionId is now a real backend-sent value (was previously undefined
    // FE-side, making this invalidation a silent no-op -- see StocktakingResourceTest
    // `count order review view carries the owning session id`).
    expect(invalidatedKeys).toContainEqual(['count-sessions', mockOrder.sessionId]);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useRecount', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-orders/:id/recount with empty body', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 50 });
    const { result } = renderHook(() => useRecount(10), { wrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/count-orders/10/recount', {});
  });

  // M-1 (final review): a recount regenerates a GENERATED(50) order, re-entering
  // the claimable COUNT pool -- the work inbox (['work', ...] queries) must go
  // stale too.
  it('invalidates count-sessions, session, order AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 50 });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useRecount(10), { wrapper: qcWrapper });
    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    // mockOrder.sessionId is now a real backend-sent value (was previously undefined
    // FE-side, making this invalidation a silent no-op -- see StocktakingResourceTest
    // `count order review view carries the owning session id`).
    expect(invalidatedKeys).toContainEqual(['count-sessions', mockOrder.sessionId]);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useCancelOrder', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-orders/:id/cancel with empty body, id passed at mutate-time', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 800 });
    const { result } = renderHook(() => useCancelOrder(), { wrapper });
    result.current.mutate(10);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/count-orders/10/cancel', {});
  });

  // St7: a cancelled order leaves the claimable/claimed COUNT pool (and may close its
  // session) -- the work inbox (['work', ...] queries) must go stale too.
  it('invalidates count-sessions, session, order AND work on success', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockOrder, state: 800 });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useCancelOrder(), { wrapper: qcWrapper });
    result.current.mutate(10);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-sessions']);
    expect(invalidatedKeys).toContainEqual(['count-sessions', mockOrder.sessionId]);
    expect(invalidatedKeys).toContainEqual(['work']);
  });
});

describe('useCampaigns', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-campaigns', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue([mockCampaign]);
    const { result } = renderHook(() => useCampaigns(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-campaigns');
    expect(result.current.data?.[0].campaignNumber).toBe('CC-0001');
  });
});

describe('useCampaign', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/count-campaigns/:id (rollup view)', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue(mockCampaignRollup);
    const { result } = renderHook(() => useCampaign(5), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/count-campaigns/5');
    expect(result.current.data?.sessions).toBe(2);
  });

  it('does not fetch when id is undefined', () => {
    const { result } = renderHook(() => useCampaign(undefined), { wrapper });
    expect(result.current.fetchStatus).toBe('idle');
    expect(api.get).not.toHaveBeenCalled();
  });
});

describe('useCreateCampaign', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-campaigns and invalidates the campaigns list', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(mockCampaign);
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useCreateCampaign(), { wrapper: qcWrapper });
    result.current.mutate({ name: 'Q3 sweep', type: 'CYCLE' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith('/api/v1/count-campaigns', { name: 'Q3 sweep', type: 'CYCLE' });
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-campaigns']);
  });
});

describe('useCloseCampaign', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to /api/v1/count-campaigns/:id/close, id passed at mutate-time', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({ ...mockCampaign, state: 700 });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const qcWrapper = ({ children }: { children: React.ReactNode }) =>
      React.createElement(QueryClientProvider, { client: qc }, children);

    const { result } = renderHook(() => useCloseCampaign(), { wrapper: qcWrapper });
    result.current.mutate(5);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith('/api/v1/count-campaigns/5/close', {});
    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['count-campaigns']);
    expect(invalidatedKeys).toContainEqual(['count-campaigns', 5]);
  });
});

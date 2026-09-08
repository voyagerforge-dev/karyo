import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useMonitors } = await import('@/pages/monitors/use-monitors');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const MONITOR_DTO = {
  key: 'stuck-order',
  name: 'Stuck order',
  metric: 'Order idle in a non-terminal state',
  op: '>',
  threshold: 24,
  unit: 'h',
  severity: 'HIGH',
  scope: 'Fulfillment',
  enabled: true,
  channels: { push: true, email: false, slack: true },
  firing: true,
  lastFired: new Date().toISOString(),
};

const ALERT_DTO = {
  id: 42,
  monitorKey: 'stuck-order',
  monitorName: 'Stuck order',
  severity: 'HIGH',
  status: 'FIRING',
  scope: 'Fulfillment',
  reason: 'Order ORD-1 idle for 26h',
  suggestedFix: 'Investigate and progress the stalled order.',
  observedValue: 26,
  firstFiredAt: new Date().toISOString(),
  lastSeenAt: new Date().toISOString(),
  resolvedAt: null,
};

describe('useMonitors', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/monitors')) return Promise.resolve([MONITOR_DTO]);
      if (url.startsWith('/api/v1/alerts')) return Promise.resolve([ALERT_DTO]);
      return Promise.resolve([]);
    });
  });

  it('fetches monitors + firing alerts and maps them into the view model', async () => {
    const { result } = renderHook(() => useMonitors(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.monitors).toHaveLength(1));

    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/monitors');
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/alerts?status=FIRING');

    const m = result.current.monitors[0];
    expect(m).toMatchObject({
      id: 'stuck-order',
      name: 'Stuck order',
      op: '>',
      threshold: 24,
      unit: 'h',
      severity: 'High',
      status: 'firing',
      enabled: true,
      fired: 'Order ORD-1 idle for 26h',
      fix: 'Investigate and progress the stalled order.',
    });
    expect(result.current.firingCount).toBe(1);
    expect(result.current.selected.id).toBe('stuck-order');
  });

  it('mute() PATCHes enabled:false for the monitor', async () => {
    mockApi.patch.mockResolvedValue({ ...MONITOR_DTO, enabled: false, firing: false });
    const { result } = renderHook(() => useMonitors(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.monitors).toHaveLength(1));

    await act(async () => {
      result.current.mute('stuck-order');
    });

    expect(mockApi.patch).toHaveBeenCalledWith('/api/v1/monitors/stuck-order', { enabled: false });
  });

  it('applyFix() resolves the matching firing alert by id', async () => {
    mockApi.post.mockResolvedValue({ ...ALERT_DTO, status: 'RESOLVED' });
    const { result } = renderHook(() => useMonitors(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.monitors).toHaveLength(1));

    await act(async () => {
      result.current.applyFix('stuck-order');
    });

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/alerts/42/resolve', {});
  });

  it('stepThreshold() PATCHes the selected monitor threshold', async () => {
    mockApi.patch.mockResolvedValue(MONITOR_DTO);
    const { result } = renderHook(() => useMonitors(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.monitors).toHaveLength(1));

    await act(async () => {
      result.current.stepThreshold(1);
    });

    expect(mockApi.patch).toHaveBeenCalledWith('/api/v1/monitors/stuck-order', { threshold: 25 });
  });

  it('toggleChannel() PATCHes the full channels map, preserving sibling flags', async () => {
    // Sibling flags (push, slack) must survive the spread untouched — this is
    // the regression the assertion exists to catch (e.g. a `toggleChannel`
    // that drops `...selected.ch` and PATCHes only the flipped key).
    mockApi.get.mockImplementation((url: string) => {
      if (url.startsWith('/api/v1/monitors')) {
        return Promise.resolve([
          { ...MONITOR_DTO, channels: { push: false, email: false, slack: true } },
        ]);
      }
      if (url.startsWith('/api/v1/alerts')) return Promise.resolve([ALERT_DTO]);
      return Promise.resolve([]);
    });
    mockApi.patch.mockResolvedValue(MONITOR_DTO);
    const { result } = renderHook(() => useMonitors(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.monitors).toHaveLength(1));
    expect(result.current.selected.ch).toEqual({ push: false, email: false, slack: true });

    await act(async () => {
      result.current.toggleChannel('email');
    });

    expect(mockApi.patch).toHaveBeenCalledWith('/api/v1/monitors/stuck-order', {
      channels: { push: false, email: true, slack: true },
    });
  });
});

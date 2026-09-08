import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';

const mockApi = { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { SampleDataProvider } = await import(
  '@/features/sample-data/sample-data-provider'
);
const { useSampleData } = await import('@/features/sample-data/use-sample-data');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(
      QueryClientProvider,
      { client: qc },
      createElement(SampleDataProvider, null, children),
    );
}

const SUMMARY = {
  locations: 40,
  skus: 24,
  orders: 960,
  picks: 2400,
  shipments: 900,
  counts: 30,
  goodsReceipts: 120,
  transportOrders: 300,
  alertsTripped: ['stuck-order', 'expiry-risk'],
};

describe('SampleDataProvider / useSampleData', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loadSampleData calls POST /api/v1/demo/seed exactly once and reaches loaded with the summary', async () => {
    mockApi.post.mockResolvedValue(SUMMARY);
    const { result } = renderHook(() => useSampleData(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.loadSampleData();
    });

    expect(mockApi.post).toHaveBeenCalledTimes(1);
    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/demo/seed', {});
    await waitFor(() => expect(result.current.state.phase).toBe('loaded'));
    expect(result.current.state.summary).toEqual(SUMMARY);
    expect(result.current.state.error).toBeNull();
  });

  it('loadSampleData surfaces a failure as an error state without touching demo/reset', async () => {
    mockApi.post.mockRejectedValue(new Error('seed failed'));
    const { result } = renderHook(() => useSampleData(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.loadSampleData();
    });

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/demo/seed', {});
    await waitFor(() => expect(result.current.state.phase).toBe('idle'));
    expect(result.current.state.error).toBe('seed failed');
  });

  it('resetSampleData calls POST /api/v1/demo/reset exactly once and returns to idle', async () => {
    mockApi.post.mockResolvedValue(undefined);
    const { result } = renderHook(() => useSampleData(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.resetSampleData();
    });

    expect(mockApi.post).toHaveBeenCalledTimes(1);
    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/demo/reset', {});
    await waitFor(() => expect(result.current.state.phase).toBe('idle'));
    expect(result.current.state.summary).toBeNull();
  });

  it('resetSampleData surfaces a failure as an error state', async () => {
    mockApi.post.mockRejectedValue(new Error('reset failed'));
    const { result } = renderHook(() => useSampleData(), { wrapper: wrapper() });

    await act(async () => {
      await result.current.resetSampleData();
    });

    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/demo/reset', {});
    await waitFor(() => expect(result.current.state.phase).toBe('idle'));
    expect(result.current.state.error).toBe('reset failed');
  });
});

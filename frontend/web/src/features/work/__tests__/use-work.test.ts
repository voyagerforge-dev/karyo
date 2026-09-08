import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import { useAvailableWork, useMyWork, useClaimWork, useReleaseWork } from '../use-work';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class extends Error {},
}));

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: qc }, children);
};

describe('useAvailableWork', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches available work without type filter', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      { ref: 'PICK:1', workType: 'PICK', priority: 1, state: 'OPEN', claimedBy: null, zone: 'A', primaryLocation: 'A-01-01', destination: null, summary: 'Pick item', createdAt: '2026-07-21T00:00:00Z' },
    ]);
    const { result } = renderHook(() => useAvailableWork(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/work/available');
    expect(result.current.data).toHaveLength(1);
  });

  it('fetches available work with type filter', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      { ref: 'PICK:1', workType: 'PICK', priority: 1, state: 'OPEN', claimedBy: null, zone: 'A', primaryLocation: 'A-01-01', destination: null, summary: 'Pick item', createdAt: '2026-07-21T00:00:00Z' },
    ]);
    const { result } = renderHook(() => useAvailableWork('PICK'), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/work/available?type=PICK');
    expect(result.current.data).toHaveLength(1);
  });
});

describe('useMyWork', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches my claimed work', async () => {
    (api.get as ReturnType<typeof vi.fn>).mockResolvedValue([
      { ref: 'PICK:2', workType: 'PICK', priority: 2, state: 'CLAIMED', claimedBy: 'user123', zone: 'B', primaryLocation: 'B-01-01', destination: null, summary: 'Pick item', createdAt: '2026-07-21T00:00:00Z' },
    ]);
    const { result } = renderHook(() => useMyWork(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.get).toHaveBeenCalledWith('/api/v1/work/mine');
    expect(result.current.data).toHaveLength(1);
  });
});

describe('useClaimWork', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to claim endpoint with encoded ref', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      ref: 'PICK:1',
      workType: 'PICK',
      priority: 1,
      state: 'CLAIMED',
      claimedBy: 'user123',
      zone: 'A',
      primaryLocation: 'A-01-01',
      destination: null,
      summary: 'Pick item',
      createdAt: '2026-07-21T00:00:00Z',
    });
    const { result } = renderHook(() => useClaimWork(), { wrapper });
    result.current.mutate('PICK:1');
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/work/PICK%3A1/claim', {});
  });
});

describe('useReleaseWork', () => {
  beforeEach(() => vi.clearAllMocks());

  it('POSTs to release endpoint with encoded ref', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      ref: 'PICK:1',
      workType: 'PICK',
      priority: 1,
      state: 'OPEN',
      claimedBy: null,
      zone: 'A',
      primaryLocation: 'A-01-01',
      destination: null,
      summary: 'Pick item',
      createdAt: '2026-07-21T00:00:00Z',
    });
    const { result } = renderHook(() => useReleaseWork(), { wrapper });
    result.current.mutate('PICK:1');
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.post).toHaveBeenCalledWith('/api/v1/work/PICK%3A1/release', {});
  });
});

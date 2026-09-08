import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { JournalEntry } from '../use-journals';

const mockApi = { get: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { signedDelta, recordTypeMeta, useOrderActivity } = await import('../use-journals');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

function entry(over: Partial<JournalEntry>): JournalEntry {
  return {
    recordType: 3, recordTypeName: 'PICKED', productNumber: 'SKU', amount: 10,
    fromStorageLocation: null, toStorageLocation: null, lotNumber: null,
    correlationId: null, created: '2026-06-01T10:00:00Z', ...over,
  };
}

describe('signedDelta', () => {
  it('is negative when the location is the source, positive when the destination, 0 otherwise', () => {
    expect(signedDelta(entry({ fromStorageLocation: 'A', amount: 8 }), 'A')).toBe(-8);
    expect(signedDelta(entry({ toStorageLocation: 'A', amount: 9 }), 'A')).toBe(9);
    expect(signedDelta(entry({ fromStorageLocation: 'B', amount: 5 }), 'A')).toBe(0);
    expect(signedDelta(entry({ amount: null, toStorageLocation: 'A' }), 'A')).toBe(0);
  });
});

describe('recordTypeMeta', () => {
  it('maps record types to label + dot color', () => {
    expect(recordTypeMeta(1).label).toBe('Receipt');
    expect(recordTypeMeta(3).label).toBe('Pick');
    expect(recordTypeMeta(7).label).toBe('Count adjust');
    expect(recordTypeMeta(99).label).toBe('Adjust');
  });
});

describe('useOrderActivity', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs the journals endpoint filtered by correlationId=orderNumber', async () => {
    mockApi.get.mockResolvedValue([entry({ correlationId: 'DO-1001' })]);
    const { result } = renderHook(() => useOrderActivity('DO-1001'), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/journals?correlationId=DO-1001');
    expect(result.current.data).toHaveLength(1);
  });

  it('is disabled (no fetch) when orderNumber is empty', async () => {
    const { result } = renderHook(() => useOrderActivity(''), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(mockApi.get).not.toHaveBeenCalled();
    expect(result.current.data).toEqual([]);
  });

  it('is disabled when the enabled flag is explicitly false', async () => {
    const { result } = renderHook(() => useOrderActivity('DO-1001', false), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(mockApi.get).not.toHaveBeenCalled();
  });
});

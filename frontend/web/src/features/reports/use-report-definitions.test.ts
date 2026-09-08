import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), post: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const {
  useReportDefinitions,
  useCreateReportDefinition,
  useDeleteReportDefinition,
} = await import('@/features/reports/use-report-definitions');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useReportDefinitions', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches saved reports from the API', async () => {
    mockApi.get.mockResolvedValue([
      { id: 1, name: 'Daily throughput', reportType: 'throughput', params: '{}', owner: 'demo-seed', created: '2026-07-01T00:00:00Z' },
    ]);
    const { result } = renderHook(() => useReportDefinitions(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/report-definitions');
    expect(result.current.data?.[0].name).toBe('Daily throughput');
  });
});

describe('useCreateReportDefinition', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts a new report definition', async () => {
    mockApi.post.mockResolvedValue({ id: 2, name: 'Aging stock', reportType: 'aging-stock', params: '{}', owner: null, created: '2026-07-02T00:00:00Z' });
    const { result } = renderHook(() => useCreateReportDefinition(), { wrapper: wrapper() });
    result.current.mutate({ name: 'Aging stock', reportType: 'aging-stock' });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.post).toHaveBeenCalledWith('/api/v1/report-definitions', { name: 'Aging stock', reportType: 'aging-stock' });
  });
});

describe('useDeleteReportDefinition', () => {
  beforeEach(() => vi.clearAllMocks());

  it('deletes a report definition by id', async () => {
    mockApi.delete.mockResolvedValue(undefined);
    const { result } = renderHook(() => useDeleteReportDefinition(), { wrapper: wrapper() });
    result.current.mutate(1);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.delete).toHaveBeenCalledWith('/api/v1/report-definitions/1');
  });
});

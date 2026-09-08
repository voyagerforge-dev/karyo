import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useForecasts } = await import('@/pages/insights/use-forecasts');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const FORECAST_DTO = {
  sku: 'SKU-1',
  avgDailyDemand: 4.2,
  forecastNextNDays: 29.4,
  currentOnHand: 12,
  suggestedReorderPoint: 20,
  suggestedReorderQty: 40,
  belowReorderPoint: true,
  confidence: 'HIGH',
};

describe('useForecasts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockResolvedValue([FORECAST_DTO]);
  });

  it('GETs /api/v1/forecasts and returns the rows', async () => {
    const { result } = renderHook(() => useForecasts(), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.data).toHaveLength(1));

    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/forecasts');
    expect(result.current.data?.[0]).toMatchObject(FORECAST_DTO);
  });
});

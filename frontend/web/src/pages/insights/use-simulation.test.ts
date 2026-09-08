import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { useSimulation } = await import('@/pages/insights/use-simulation');

function wrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

const RESPONSE = {
  summary: { skusSimulated: 1, totalStockoutDaysAvoided: 4, avgFillRateDelta: 0.4, totalAvgOnHandDelta: 4 },
  rows: [
    {
      sku: 'SKU-1',
      confidence: 'HIGH',
      baselineStockoutDays: 5,
      suggestedStockoutDays: 1,
      stockoutDaysAvoided: 4,
      baselineFillRate: 0.5,
      suggestedFillRate: 0.9,
      fillRateDelta: 0.4,
      baselineAvgOnHand: 2,
      suggestedAvgOnHand: 6,
      avgOnHandDelta: 4,
      baselineReorderPoint: 28,
      suggestedReorderPoint: 40,
      orderQty: 56,
    },
  ],
};

describe('useSimulation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockApi.get.mockResolvedValue(RESPONSE);
  });

  it('GETs /api/v1/simulations/reorder and returns the response', async () => {
    const { result } = renderHook(() => useSimulation(), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/simulations/reorder');
    expect(result.current.data?.summary.totalStockoutDaysAvoided).toBe(4);
    expect(result.current.data?.rows).toHaveLength(1);
  });
});

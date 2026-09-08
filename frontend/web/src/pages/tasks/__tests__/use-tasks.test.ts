import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import {
  useCompleteTask,
  useCancelTask,
  useCreateManualMove,
  usePauseTask,
  useResumeTask,
} from '../use-tasks';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class extends Error {},
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

const task = {
  id: 2,
  orderNumber: 'TO-2002',
  transportType: 'PUTAWAY',
  destinationLocationName: 'STR-01',
};

function makeWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client: queryClient }, children);
  return { wrapper, invalidateSpy };
}

// The unified /tasks work inbox (features/work/use-work.ts) reads ['work', ...]
// queries. Completing/canceling/creating a transport task must invalidate that
// key too, or the inbox keeps showing rows that have already left the pool.
describe('useInvalidateTasks (via useCompleteTask/useCancelTask/useCreateManualMove)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('useCompleteTask invalidates transport-orders, stock-units, and the work inbox', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(task);
    const { wrapper, invalidateSpy } = makeWrapper();
    const { result } = renderHook(() => useCompleteTask(), { wrapper });

    result.current.mutate({ id: 2 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const keys = invalidateSpy.mock.calls.map((call) => call[0]?.queryKey);
    expect(keys).toContainEqual(['transport-orders']);
    expect(keys).toContainEqual(['transport-orders', 'detail', 2]);
    expect(keys).toContainEqual(['stock-units']);
    expect(keys).toContainEqual(['work']);
  });

  it('useCancelTask invalidates transport-orders, stock-units, and the work inbox', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(task);
    const { wrapper, invalidateSpy } = makeWrapper();
    const { result } = renderHook(() => useCancelTask(), { wrapper });

    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const keys = invalidateSpy.mock.calls.map((call) => call[0]?.queryKey);
    expect(keys).toContainEqual(['transport-orders']);
    expect(keys).toContainEqual(['stock-units']);
    expect(keys).toContainEqual(['work']);
  });

  it('useCreateManualMove invalidates transport-orders, stock-units, and the work inbox', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(task);
    const { wrapper, invalidateSpy } = makeWrapper();
    const { result } = renderHook(() => useCreateManualMove(), { wrapper });

    result.current.mutate({
      unitLoadId: 11,
      destinationLocationId: 5,
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const keys = invalidateSpy.mock.calls.map((call) => call[0]?.queryKey);
    expect(keys).toContainEqual(['transport-orders']);
    expect(keys).toContainEqual(['stock-units']);
    expect(keys).toContainEqual(['work']);
  });
});

// PT18: pause/resume are id-only POSTs against `.../pause` and `.../resume`
// with an empty body (the pattern receiving's usePauseReceipt/useResumeReceipt
// established), invalidating the same keys as the other lifecycle mutations.
describe('usePauseTask / useResumeTask', () => {
  beforeEach(() => vi.clearAllMocks());

  it('usePauseTask posts to /pause with an empty body and invalidates the task + work inbox', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(task);
    const { wrapper, invalidateSpy } = makeWrapper();
    const { result } = renderHook(() => usePauseTask(), { wrapper });

    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith('/api/v1/transport-orders/2/pause', {});
    const keys = invalidateSpy.mock.calls.map((call) => call[0]?.queryKey);
    expect(keys).toContainEqual(['transport-orders']);
    expect(keys).toContainEqual(['transport-orders', 'detail', 2]);
    expect(keys).toContainEqual(['work']);
  });

  it('useResumeTask posts to /resume with an empty body and invalidates the task + work inbox', async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue(task);
    const { wrapper, invalidateSpy } = makeWrapper();
    const { result } = renderHook(() => useResumeTask(), { wrapper });

    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(api.post).toHaveBeenCalledWith('/api/v1/transport-orders/2/resume', {});
    const keys = invalidateSpy.mock.calls.map((call) => call[0]?.queryKey);
    expect(keys).toContainEqual(['transport-orders']);
    expect(keys).toContainEqual(['transport-orders', 'detail', 2]);
    expect(keys).toContainEqual(['work']);
  });
});

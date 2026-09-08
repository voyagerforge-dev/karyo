import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import {
  useManifest,
  useDispatch,
  useClaimShipment,
  useReleaseShipment,
  usePauseShipment,
  useResumeShipment,
  useCancelShipment,
  useRemoveShippingUnit,
  useAddAdHocUnit,
} from '../use-shipping';
import { api } from '@/lib/api-client';

vi.mock('@/lib/api-client', () => ({ api: { post: vi.fn(), delete: vi.fn() } }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return createElement(QueryClientProvider, { client: qc }, children);
}

/** Builds a wrapper around a fresh QueryClient with `invalidateQueries` spied on, for tests
 * that need to inspect exactly which query keys a mutation invalidates. */
function spiedWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
  const qcWrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
  return { invalidateSpy, qcWrapper };
}

beforeEach(() => vi.clearAllMocks());

describe('use-shipping', () => {
  it('useManifest posts carrier body to the manifest endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1' });
    const { result } = renderHook(() => useManifest(), { wrapper });
    act(() => result.current.mutate({ shipmentId: 1, body: { carrierName: 'UPS', carrierService: 'GROUND' } }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/manifest', { carrierName: 'UPS', carrierService: 'GROUND' }));
  });

  it('useDispatch posts to the dispatch endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1' });
    const { result } = renderHook(() => useDispatch(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/dispatch', {}));
  });
});

describe('use-shipping -- Task 9 lifecycle mutations (S3)', () => {
  it('useClaimShipment posts to the claim endpoint with no body', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', operatorId: 'op-alice' });
    const { result } = renderHook(() => useClaimShipment(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/claim', {}));
  });

  it('useReleaseShipment posts to the release endpoint with no body', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', operatorId: null });
    const { result } = renderHook(() => useReleaseShipment(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/release', {}));
  });

  it('usePauseShipment posts to the pause endpoint with no body', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', pausedAt: '2026-08-15T10:00:00Z' });
    const { result } = renderHook(() => usePauseShipment(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/pause', {}));
  });

  it('useResumeShipment posts to the resume endpoint with no body', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', pausedAt: null });
    const { result } = renderHook(() => useResumeShipment(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/resume', {}));
  });

  it('useClaimShipment invalidates shipments (list + detail) and orders on success', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', operatorId: 'op-alice' });
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useClaimShipment(), { wrapper: qcWrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['shipments']);
    expect(invalidatedKeys).toContainEqual(['shipments', 1]);
    expect(invalidatedKeys).toContainEqual(['orders']);
  });
});

describe('use-shipping -- Task 9 cancel + unit removal (S4)', () => {
  it('useCancelShipment posts to the cancel endpoint with no body', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', state: 800 });
    const { result } = renderHook(() => useCancelShipment(), { wrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/cancel', {}));
  });

  it('useCancelShipment invalidates shipments (list + detail) and orders on success', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1', state: 800 });
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useCancelShipment(), { wrapper: qcWrapper });
    act(() => result.current.mutate(1));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['shipments']);
    expect(invalidatedKeys).toContainEqual(['shipments', 1]);
    expect(invalidatedKeys).toContainEqual(['orders']);
  });

  it('useRemoveShippingUnit DELETEs the shipping-unit sub-resource', async () => {
    vi.mocked(api.delete).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1' });
    const { result } = renderHook(() => useRemoveShippingUnit(), { wrapper });
    act(() => result.current.mutate({ shipmentId: 1, unitId: 500 }));
    await waitFor(() =>
      expect(api.delete).toHaveBeenCalledWith('/api/v1/shipments/1/shipping-units/500'),
    );
  });
});

describe('use-shipping -- Task 9 ad-hoc unit attach (S5)', () => {
  it('useAddAdHocUnit posts the unit load id to the shipping-units endpoint', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1' });
    const { result } = renderHook(() => useAddAdHocUnit(), { wrapper });
    act(() => result.current.mutate({ shipmentId: 1, unitLoadId: 77 }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith('/api/v1/shipments/1/shipping-units', { unitLoadId: 77 }),
    );
  });

  it('useAddAdHocUnit invalidates shipments (list + detail) and orders on success', async () => {
    vi.mocked(api.post).mockResolvedValue({ id: 1, shipmentNumber: 'SHP-1' });
    const { invalidateSpy, qcWrapper } = spiedWrapper();
    const { result } = renderHook(() => useAddAdHocUnit(), { wrapper: qcWrapper });
    act(() => result.current.mutate({ shipmentId: 1, unitLoadId: 77 }));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const invalidatedKeys = invalidateSpy.mock.calls.map(
      (call) => (call[0] as { queryKey: unknown[] }).queryKey,
    );
    expect(invalidatedKeys).toContainEqual(['shipments']);
    expect(invalidatedKeys).toContainEqual(['shipments', 1]);
    expect(invalidatedKeys).toContainEqual(['orders']);
  });
});

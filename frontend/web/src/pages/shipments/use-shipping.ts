import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type { AddAdHocUnitRequest, ManifestRequest, Shipment } from '@/types/shipments';

export { useShipments, useShipment } from '@/pages/packing/use-packing';

function useInvalidateShipping() {
  const queryClient = useQueryClient();
  return (id?: number) => {
    void queryClient.invalidateQueries({ queryKey: ['shipments'] });
    if (id != null) void queryClient.invalidateQueries({ queryKey: ['shipments', id] });
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
  };
}

/** Manifest a PACKED shipment (assign carrier). `mutate` takes { shipmentId, body }. */
export function useManifest() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: ({ shipmentId, body }: { shipmentId: number; body: ManifestRequest }) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/manifest`, body),
    onSuccess: (s) => {
      invalidate(s.id);
      toast.success(`Shipment ${s.shipmentNumber} manifested`);
    },
  });
}

/** Dispatch a SHIPPING shipment. `mutate` takes a bare shipmentId. */
export function useDispatch() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: (shipmentId: number) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/dispatch`, {}),
    onSuccess: (s) => {
      invalidate(s.id);
      toast.success(`Shipment ${s.shipmentNumber} dispatched`);
    },
  });
}

/** S3: claim a shipment for the calling operator. `mutate` takes a bare shipmentId. */
export function useClaimShipment() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: (shipmentId: number) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/claim`, {}),
    onSuccess: (s) => invalidate(s.id),
  });
}

/** S3: release the claim on a shipment. `mutate` takes a bare shipmentId. */
export function useReleaseShipment() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: (shipmentId: number) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/release`, {}),
    onSuccess: (s) => invalidate(s.id),
  });
}

/** S3: pause a shipment (stamps pausedAt; state does not move). */
export function usePauseShipment() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: (shipmentId: number) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/pause`, {}),
    onSuccess: (s) => invalidate(s.id),
  });
}

/** S3: resume a paused shipment (clears pausedAt). */
export function useResumeShipment() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: (shipmentId: number) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/resume`, {}),
    onSuccess: (s) => invalidate(s.id),
  });
}

/**
 * S4: cancel a pre-manifest shipment -- restores every shipping unit's stock per origin and
 * moves the shipment to CANCELED. 409 `shipment-not-cancelable` once the shipment has passed
 * PACKED, surfaced to the caller via the api-client's global toast.
 */
export function useCancelShipment() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: (shipmentId: number) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/cancel`, {}),
    onSuccess: (s) => {
      invalidate(s.id);
      toast.success(`Shipment ${s.shipmentNumber} canceled`);
    },
  });
}

/**
 * S4: remove one shipping unit from a pre-manifest shipment, restoring its unit-load stock per
 * origin. `mutate` takes { shipmentId, unitId }.
 */
export function useRemoveShippingUnit() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: ({ shipmentId, unitId }: { shipmentId: number; unitId: number }) =>
      api.delete<Shipment>(`/api/v1/shipments/${shipmentId}/shipping-units/${unitId}`),
    onSuccess: (s) => invalidate(s.id),
  });
}

/**
 * S5: attach an ad-hoc ON_STOCK unit load to a shipment as a new shipping unit (no pick order
 * behind it). `mutate` takes { shipmentId, unitLoadId }.
 */
export function useAddAdHocUnit() {
  const invalidate = useInvalidateShipping();
  return useMutation({
    mutationFn: ({ shipmentId, unitLoadId }: { shipmentId: number } & AddAdHocUnitRequest) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/shipping-units`, { unitLoadId }),
    onSuccess: (s) => {
      invalidate(s.id);
      toast.success(`Unit load attached to ${s.shipmentNumber}`);
    },
  });
}

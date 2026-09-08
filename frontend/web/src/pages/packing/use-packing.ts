import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import { usePickOrders } from '@/features/picking/use-pick-orders';
import type { PickOrderResponse } from '@/types/pick-orders';
import type { Shipment } from '@/types/shipments';

/**
 * Fetch all shipments (plain array — the shipments list endpoint is not paginated).
 */
export function useShipments() {
  return useQuery({
    queryKey: ['shipments'],
    queryFn: () => api.get<Shipment[]>('/api/v1/shipments'),
    staleTime: 10_000,
  });
}

/**
 * Fetch a single shipment with its shipping units.
 */
export function useShipment(id: number | undefined) {
  return useQuery({
    queryKey: ['shipments', id],
    queryFn: () => api.get<Shipment>(`/api/v1/shipments/${id}`),
    enabled: id != null,
    staleTime: 5_000,
  });
}

/** A pick order bound to a real delivery order — narrows out EXTINGUISH
 *  orders (row 20), which have no delivery order and therefore nothing to
 *  pack: they complete without ever entering the packing flow. */
export type OrderBoundPickOrder = PickOrderResponse & {
  deliveryOrderId: number;
  deliveryOrderNumber: string;
};

/**
 * Pure join: PICKED(600), order-bound pick orders whose delivery order has
 * no shipment yet.
 */
export function deriveReadyToPack(
  pickOrders: PickOrderResponse[],
  shipments: Shipment[],
): OrderBoundPickOrder[] {
  // A group shipment (Bulk Allocation Sprint C) carries no single deliveryOrderId --
  // its member orders (`orders`) are the ones already packed.
  const packedOrderIds = new Set<number>();
  for (const s of shipments) {
    if (s.deliveryOrderId != null) packedOrderIds.add(s.deliveryOrderId);
    for (const o of s.orders ?? []) packedOrderIds.add(o.id);
  }
  return pickOrders.filter(
    (po): po is OrderBoundPickOrder =>
      po.state === 600 && po.deliveryOrderId !== null && !packedOrderIds.has(po.deliveryOrderId),
  );
}

/**
 * Orders ready to pack — derived client-side from pick-orders + shipments.
 */
export function useReadyToPack() {
  const pickOrders = usePickOrders();
  const shipments = useShipments();
  return {
    data: deriveReadyToPack(pickOrders.data ?? [], shipments.data ?? []),
    isLoading: pickOrders.isLoading || shipments.isLoading,
  };
}

function useInvalidatePacking() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['shipments'] });
    void queryClient.invalidateQueries({ queryKey: ['pick-orders'] });
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
  };
}

/**
 * Open packing for a delivery order: creates a Shipment (PACKING). `mutate` takes
 * a bare deliveryOrderId.
 */
export function useOpenPacking() {
  const invalidate = useInvalidatePacking();
  return useMutation({
    mutationFn: (deliveryOrderId: number) =>
      api.post<Shipment>('/api/v1/shipments', { deliveryOrderId }),
    onSuccess: (s) => {
      invalidate();
      toast.success(`Shipment ${s.shipmentNumber} opened`);
    },
  });
}

/**
 * Pack a shipment: weigh + confirm. `mutate` takes { shipmentId, body }.
 */
export function usePackShipment() {
  const invalidate = useInvalidatePacking();
  return useMutation({
    mutationFn: ({ shipmentId, body }: { shipmentId: number; body: { weight: number; type: string } }) =>
      api.post<Shipment>(`/api/v1/shipments/${shipmentId}/pack`, body),
    onSuccess: (s) => {
      invalidate();
      toast.success(`Shipment ${s.shipmentNumber} packed`);
    },
  });
}

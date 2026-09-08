import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type {
  BulkConfirmResponse,
  BulkLineResponse,
  ConfirmPickRequest,
  ExtinguishRequest,
  PickOrderResponse,
  PickResponse,
} from '@/types/pick-orders';

/**
 * Fetch all pick orders (plain array — the pick-orders list endpoint is not paginated).
 */
export function usePickOrders() {
  return useQuery({
    queryKey: ['pick-orders'],
    queryFn: () => api.get<PickOrderResponse[]>('/api/v1/pick-orders'),
    staleTime: 10_000,
  });
}

/**
 * Fetch a single pick order with its picks.
 */
export function usePickOrder(id: number | undefined) {
  return useQuery({
    queryKey: ['pick-orders', id],
    queryFn: () => api.get<PickOrderResponse>(`/api/v1/pick-orders/${id}`),
    enabled: id != null,
    staleTime: 5_000,
  });
}

function useInvalidatePicking() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['pick-orders'] });
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
    // The unified work inbox (/tasks) reads its own ['work'] queries — a
    // confirmed FINAL pick moves the PickOrder out of the claimable/claimed
    // pool (PickOrderRepository.findClaimable/findClaimedBy), so without this
    // the row + pane stay visible until an unrelated refetch happens.
    void queryClient.invalidateQueries({ queryKey: ['work'] });
  };
}

/**
 * Release a delivery order to picking: creates a pick order + picks off the
 * order's reservation slices. `mutate` takes a bare deliveryOrderId.
 *
 * Row 8: the backend now returns a JSON ARRAY in every case (`createTypeOrders` can split one
 * release into several PickOrders), not sometimes an object and sometimes an array.
 */
export function useReleaseToPicking() {
  const invalidate = useInvalidatePicking();
  return useMutation({
    mutationFn: (deliveryOrderId: number) =>
      api.post<PickOrderResponse[]>('/api/v1/pick-orders', { deliveryOrderId }),
    onSuccess: (pickOrders) => {
      invalidate();
      const label = pickOrders.map((po) => po.pickOrderNumber).join(', ');
      toast.success(
        pickOrders.length > 1 ? `${pickOrders.length} pick orders created: ${label}` : `Pick order ${label} created`,
      );
    },
  });
}

/**
 * Confirm a single pick. `mutate` takes { pickId, body }. A short pick (picked <
 * planned) surfaces a warning toast; the backend creates the recovery follow-up.
 */
export function useConfirmPick() {
  const invalidate = useInvalidatePicking();
  return useMutation({
    mutationFn: ({ pickId, body }: { pickId: number; body: ConfirmPickRequest }) =>
      api.post<PickResponse>(`/api/v1/picks/${pickId}/confirm`, body),
    onSuccess: (pick) => {
      invalidate();
      if (pick.pickedAmount < pick.plannedAmount) {
        toast.warning(
          `Pick confirmed short (${pick.pickedAmount}/${pick.plannedAmount}) — recovery created`,
        );
      } else {
        toast.success('Pick confirmed');
      }
    },
  });
}

/**
 * Fetch the aggregated bulk-pick lines (one row per source stock unit) for a
 * BULK pick order. Disabled by default until the caller knows the order is
 * bulk (`po.bulk`) -- pass `enabled=false` for a non-bulk order.
 */
export function useBulkLines(pickOrderId: number | undefined, enabled = true) {
  return useQuery({
    queryKey: ['pick-orders', pickOrderId, 'bulk-lines'],
    queryFn: () => api.get<BulkLineResponse[]>(`/api/v1/pick-orders/${pickOrderId}/bulk-lines`),
    enabled: enabled && pickOrderId !== undefined,
  });
}

/**
 * Fan-out confirm for one bulk source stock unit: distributes the picked
 * amount across the order's open batch-pick slices. `mutate` takes
 * { pickOrderId, body }.
 */
export function useBulkConfirm() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({
      pickOrderId,
      body,
    }: {
      pickOrderId: number;
      body: { sourceStockUnitId: number; pickedAmount: number; targetUnitLoadId?: number };
    }) => api.post<BulkConfirmResponse>(`/api/v1/pick-orders/${pickOrderId}/bulk-confirm`, body),
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: ['pick-orders', v.pickOrderId] });
      qc.invalidateQueries({ queryKey: ['pick-orders'] });
    },
  });
}

/**
 * Force-finish cancel a pick order (row 14 — myWMS finishPickingOrder
 * semantics). Open picks are canceled + their reservations released; the
 * order lands CANCELED (no picks were PICKED) or FINISHED (partial work
 * kept). `mutate` takes the bare pick order id; no request body.
 */
export function useCancelPickOrder() {
  const invalidate = useInvalidatePicking();
  return useMutation({
    mutationFn: (id: number) => api.post<PickOrderResponse>(`/api/v1/pick-orders/${id}/cancel`, {}),
    onSuccess: (po) => {
      invalidate();
      toast.success(`Pick order ${po.pickOrderNumber} canceled`);
    },
  });
}

/**
 * Stock-clearance (extinguish) picks (row 20): one pick per targeted stock
 * unit (or every stock unit on a unit load) for its full available amount,
 * merging into an existing open EXT- order for the same client where
 * possible. `mutate` takes the ExtinguishRequest body (exactly one of
 * stockUnitIds/unitLoadId, enforced backend-side).
 */
export function useExtinguishStock() {
  const invalidate = useInvalidatePicking();
  return useMutation({
    mutationFn: (body: ExtinguishRequest) =>
      api.post<PickOrderResponse>('/api/v1/pick-orders/extinguish', body),
    onSuccess: (po) => {
      invalidate();
      toast.success(`Stock cleared to ${po.pickOrderNumber}`);
    },
  });
}

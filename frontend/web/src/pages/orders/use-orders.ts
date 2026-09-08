import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import { sortingStateToString } from '@/lib/table-utils';
import type { PaginatedResponse } from '@/types/api';
import type {
  DeliveryOrderResponse,
  DeliveryOrderReleaseResponse,
  CreateDeliveryOrderRequest,
  UpdateDeliveryOrderRequest,
  OrderStrategyResponse,
} from '@/types/orders';

export { sortingStateToString };

interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
  /** OrderState code filter (server-side ?state=) */
  state?: number;
  /** Free-text search over orderNumber/customerName (server-side ?q=) */
  q?: string;
}

/**
 * Fetch paginated delivery orders with server-side sort/pagination/filter/search.
 */
export function useDeliveryOrders(options: UseListOptions) {
  const { page, size, sort, state, q } = options;
  return useQuery({
    queryKey: ['orders', { page, size, sort, state, q }],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (sort) params.set('sort', sort);
      if (state != null) params.set('state', String(state));
      if (q) params.set('q', q);
      return api.get<PaginatedResponse<DeliveryOrderResponse>>(
        `/api/v1/delivery-orders?${params.toString()}`,
      );
    },
    staleTime: 10_000,
  });
}

/**
 * Fetch a single delivery order (lines incl. reservedAmount/shortage/state).
 */
export function useDeliveryOrder(id: number | undefined) {
  return useQuery({
    queryKey: ['orders', 'detail', id],
    queryFn: () => api.get<DeliveryOrderResponse>(`/api/v1/delivery-orders/${id}`),
    enabled: id != null,
    staleTime: 5_000,
  });
}

function useInvalidateOrders() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ['orders'] });
}

/**
 * Create a new delivery order with lines.
 */
export function useCreateDeliveryOrder() {
  const invalidate = useInvalidateOrders();
  return useMutation({
    mutationFn: (data: CreateDeliveryOrderRequest) =>
      api.post<DeliveryOrderResponse>('/api/v1/delivery-orders', data),
    onSuccess: (order) => {
      invalidate();
      toast.success(`Order ${order.orderNumber} created`);
    },
  });
}

/**
 * Update order header fields (only allowed while CREATED; backend returns 409 otherwise).
 */
export function useUpdateDeliveryOrder() {
  const invalidate = useInvalidateOrders();
  return useMutation({
    mutationFn: ({ id, ...data }: UpdateDeliveryOrderRequest & { id: number }) =>
      api.put<DeliveryOrderResponse>(`/api/v1/delivery-orders/${id}`, data),
    onSuccess: () => {
      invalidate();
      toast.success('Order updated');
    },
  });
}

/**
 * Release a CREATED order: reserves stock per line; response carries the
 * shortage report (empty = fully reserved, order PROCESSABLE).
 */
export function useReleaseOrder() {
  const invalidate = useInvalidateOrders();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<DeliveryOrderReleaseResponse>(`/api/v1/delivery-orders/${id}/release`, {}),
    onSuccess: ({ order, shortages }) => {
      invalidate();
      if (shortages.length > 0) {
        toast.warning(`Order ${order.orderNumber} released with ${shortages.length} short line(s)`);
      } else {
        toast.success(`Order ${order.orderNumber} released — fully reserved`);
      }
    },
  });
}

/**
 * Retry reservation for PENDING lines of a RELEASED order (PENDING -> PROCESSABLE hop).
 */
export function useRetryReservation() {
  const invalidate = useInvalidateOrders();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<DeliveryOrderReleaseResponse>(
        `/api/v1/delivery-orders/${id}/retry-reservation`,
        {},
      ),
    onSuccess: ({ order, shortages }) => {
      invalidate();
      if (shortages.length > 0) {
        toast.warning(`Order ${order.orderNumber}: ${shortages.length} line(s) still short`);
      } else {
        toast.success(`Order ${order.orderNumber} fully reserved`);
      }
    },
  });
}

/**
 * Cancel an order (pre-picking only): releases the unhandled stock reservations AND
 * force-finishes any pick work the order had already been released to.
 */
export function useCancelOrder() {
  const invalidate = useInvalidateOrders();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<DeliveryOrderResponse>(`/api/v1/delivery-orders/${id}/cancel`, {}),
    onSuccess: (order) => {
      invalidate();
      // The cancel cascades into fulfillment: the order's PickOrder is force-finished, so it
      // leaves the claimable/claimed pools the unified work inbox (['work']) reads and its
      // detail/list rows (['pick-orders']) go stale. Without these the canceled pick order
      // stays visible in /tasks and /picking until an unrelated refetch.
      void queryClient.invalidateQueries({ queryKey: ['work'] });
      void queryClient.invalidateQueries({ queryKey: ['pick-orders'] });
      toast.success(`Order ${order.orderNumber} canceled`);
    },
  });
}

/**
 * Row 10: claim an order for the calling operator (pure metadata; state never moves).
 */
export function useClaimOrder() {
  const invalidate = useInvalidateOrders();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<DeliveryOrderResponse>(`/api/v1/delivery-orders/${id}/claim`, {}),
    onSuccess: () => {
      invalidate();
    },
  });
}

/**
 * Row 10: release the operator claim. NOT `/release` -- that path means release-to-picking.
 */
export function useReleaseOperator() {
  const invalidate = useInvalidateOrders();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<DeliveryOrderResponse>(`/api/v1/delivery-orders/${id}/release-operator`, {}),
    onSuccess: () => {
      invalidate();
    },
  });
}

/**
 * Fetch all order strategies (flat array; backend falls back to DEFAULT when
 * an order has no strategy).
 */
export function useOrderStrategies() {
  return useQuery({
    queryKey: ['order-strategies'],
    queryFn: () => api.get<OrderStrategyResponse[]>('/api/v1/order-strategies'),
    staleTime: 60_000,
  });
}

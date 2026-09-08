import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type {
  TransportOrderResponse,
  CreateTransportOrderRequest,
  AssignTransportOrderRequest,
  CompleteTransportOrderRequest,
} from '@/types/tasks';

/** Fetch a single transport order. */
export function useTransportOrder(id: number | undefined) {
  return useQuery({
    queryKey: ['transport-orders', 'detail', id],
    queryFn: () => api.get<TransportOrderResponse>(`/api/v1/transport-orders/${id}`),
    enabled: id != null,
    staleTime: 2_000,
  });
}

/**
 * Row 22: plain paginated list, backing the tasks-page "Paused" filter. A paused
 * transport order drops out of both the available-work and claimed-by-me work-inbox
 * queries (`TransportOrderRepository.findClaimable`/`findClaimedBy` both exclude
 * `pausedAt is not null`), so it is otherwise unreachable from the Tasks page. `paused`
 * omitted (`undefined`) leaves the hook disabled -- callers opt in only when the chip
 * is active.
 */
export function useTransportOrders(options: { paused?: boolean } = {}) {
  const { paused } = options;
  return useQuery({
    queryKey: ['transport-orders', 'list', { paused }],
    queryFn: () => {
      const params = new URLSearchParams({ page: '0', size: '50' });
      if (paused != null) params.set('paused', String(paused));
      return api.get<PaginatedResponse<TransportOrderResponse>>(
        `/api/v1/transport-orders?${params.toString()}`,
      );
    },
    enabled: paused != null,
    staleTime: 10_000,
  });
}

function useInvalidateTasks() {
  const queryClient = useQueryClient();
  return (id?: number) => {
    queryClient.invalidateQueries({ queryKey: ['transport-orders'] });
    if (id != null)
      queryClient.invalidateQueries({ queryKey: ['transport-orders', 'detail', id] });
    // A completed move physically relocates stock — keep inventory views fresh.
    queryClient.invalidateQueries({ queryKey: ['stock-units'] });
    // The unified /tasks work inbox is backed by ['work', ...] queries (see
    // features/work/use-work.ts) — without this, completing/canceling/creating a
    // transport task leaves the inbox showing stale (already-gone) rows.
    queryClient.invalidateQueries({ queryKey: ['work'] });
  };
}

/** Create a manual MOVE task (PUTAWAY tasks are auto-created by receiving). */
export function useCreateManualMove() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: (data: CreateTransportOrderRequest) =>
      api.post<TransportOrderResponse>('/api/v1/transport-orders', data),
    onSuccess: (order) => {
      invalidate(order.id);
      toast.success(`Move ${order.orderNumber} created`);
    },
  });
}

/** Assign a RELEASED task to an operator (RELEASED → RESERVED). */
export function useAssignTask() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: ({ id, ...data }: AssignTransportOrderRequest & { id: number }) =>
      api.post<TransportOrderResponse>(`/api/v1/transport-orders/${id}/assign`, data),
    onSuccess: (order) => invalidate(order.id),
  });
}

/** Start a RESERVED task (RESERVED → STARTED). No request body. */
export function useStartTask() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<TransportOrderResponse>(`/api/v1/transport-orders/${id}/start`, {}),
    onSuccess: (order) => invalidate(order.id),
  });
}

/**
 * Complete a STARTED task (STARTED → FINISHED), physically moving the unit load.
 * Sending {} accepts the suggested destination; a body overrides it. The backend
 * 415s on an empty/no-content-type body, so always send a JSON object.
 */
export function useCompleteTask() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: ({ id, ...data }: CompleteTransportOrderRequest & { id: number }) =>
      api.post<TransportOrderResponse>(`/api/v1/transport-orders/${id}/complete`, data),
    onSuccess: (order) => {
      invalidate(order.id);
      toast.success(`Unit load moved to ${order.destinationLocationName ?? 'destination'}`);
    },
  });
}

/** Cancel a task (→ CANCELED). No request body. */
export function useCancelTask() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<TransportOrderResponse>(`/api/v1/transport-orders/${id}/cancel`, {}),
    onSuccess: (order) => {
      invalidate(order.id);
      toast.success(`Task ${order.orderNumber} canceled`);
    },
  });
}

/** PT18: pause a task (orthogonal to state — see TaskService.pause's KDoc). No request body. */
export function usePauseTask() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<TransportOrderResponse>(`/api/v1/transport-orders/${id}/pause`, {}),
    onSuccess: (order) => invalidate(order.id),
  });
}

/** PT18: clear a task's pause stamp. No request body. */
export function useResumeTask() {
  const invalidate = useInvalidateTasks();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<TransportOrderResponse>(`/api/v1/transport-orders/${id}/resume`, {}),
    onSuccess: (order) => invalidate(order.id),
  });
}

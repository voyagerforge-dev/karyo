import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type {
  OrderStrategyResponse,
  CreateOrderStrategyRequest,
  UpdateOrderStrategyRequest,
  StorageStrategyResponse,
  CreateStorageStrategyRequest,
} from '@/types/strategies';

// ----- Order strategies (system-level) -----
// The read hook's canonical home is the orders feature (order-form's strategy picker
// already uses it). Re-export it here so there is ONE ['order-strategies'] query
// definition/staleTime rather than two competing for the same cache key (ADR-036 review).
export { useOrderStrategies } from '@/pages/orders/use-orders';

function useInvalidateOrderStrategies() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['order-strategies'] });
}

export function useCreateOrderStrategy() {
  const invalidate = useInvalidateOrderStrategies();
  return useMutation({
    mutationFn: (data: CreateOrderStrategyRequest) =>
      api.post<OrderStrategyResponse>('/api/v1/order-strategies', data),
    onSuccess: (s) => {
      invalidate();
      toast.success(`Strategy ${s.name} created`);
    },
  });
}

export function useUpdateOrderStrategy() {
  const invalidate = useInvalidateOrderStrategies();
  return useMutation({
    mutationFn: ({ id, ...data }: UpdateOrderStrategyRequest & { id: number }) =>
      api.put<OrderStrategyResponse>(`/api/v1/order-strategies/${id}`, data),
    onSuccess: () => {
      invalidate();
      toast.success('Strategy updated');
    },
  });
}

// ----- Storage strategies (client-scoped) -----
export function useStorageStrategies() {
  return useQuery({
    queryKey: ['storage-strategies'],
    queryFn: () => api.get<StorageStrategyResponse[]>('/api/v1/storage-strategies'),
    staleTime: 30_000,
  });
}

function useInvalidateStorageStrategies() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['storage-strategies'] });
}

export function useCreateStorageStrategy() {
  const invalidate = useInvalidateStorageStrategies();
  return useMutation({
    mutationFn: (data: CreateStorageStrategyRequest) =>
      api.post<StorageStrategyResponse>('/api/v1/storage-strategies', data),
    onSuccess: (s) => {
      invalidate();
      toast.success(`Strategy ${s.name} created`);
    },
  });
}

export function useUpdateStorageStrategy() {
  const invalidate = useInvalidateStorageStrategies();
  // The backend PUT /storage-strategies/{id} consumes the same CreateStorageStrategyRequest
  // as create (storage strategies support rename), so there is deliberately no separate
  // Update DTO here — unlike order strategies, whose rename is blocked.
  return useMutation({
    mutationFn: ({ id, ...data }: CreateStorageStrategyRequest & { id: number }) =>
      api.put<StorageStrategyResponse>(`/api/v1/storage-strategies/${id}`, data),
    onSuccess: () => {
      invalidate();
      toast.success('Strategy updated');
    },
  });
}

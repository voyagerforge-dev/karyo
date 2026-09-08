import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type {
  WebhookSubscription,
  CreatedSubscription,
  WebhookDelivery,
  CreateSubscriptionInput,
} from '@/types/webhooks';

const SUBS_KEY = ['webhook-subscriptions'];
const DELIVERIES_KEY = ['webhook-deliveries'];

export function useSubscriptions() {
  return useQuery({
    queryKey: SUBS_KEY,
    queryFn: () => api.get<WebhookSubscription[]>('/api/v1/webhook-subscriptions'),
  });
}

export function useCreateSubscription() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateSubscriptionInput) =>
      api.post<CreatedSubscription>('/api/v1/webhook-subscriptions', input),
    onSuccess: () => qc.invalidateQueries({ queryKey: SUBS_KEY }),
  });
}

export function useUpdateSubscription() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...patch }: { id: number } & Partial<CreateSubscriptionInput>) =>
      api.patch<WebhookSubscription>(`/api/v1/webhook-subscriptions/${id}`, patch),
    onSuccess: () => qc.invalidateQueries({ queryKey: SUBS_KEY }),
  });
}

export function useDeleteSubscription() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete(`/api/v1/webhook-subscriptions/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: SUBS_KEY }),
  });
}

export function useTestSubscription() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post(`/api/v1/webhook-subscriptions/${id}/test`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: DELIVERIES_KEY }),
  });
}

export function useDeliveries(filter: { subscriptionId?: number; status?: string }) {
  const params = new URLSearchParams();
  if (filter.subscriptionId != null) params.set('subscriptionId', String(filter.subscriptionId));
  if (filter.status) params.set('status', filter.status);
  const qs = params.toString();
  return useQuery({
    queryKey: [...DELIVERIES_KEY, filter],
    queryFn: () =>
      api.get<WebhookDelivery[]>(`/api/v1/webhook-deliveries${qs ? `?${qs}` : ''}`),
  });
}

export function useRedeliver() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post(`/api/v1/webhook-deliveries/${id}/redeliver`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: DELIVERIES_KEY }),
  });
}

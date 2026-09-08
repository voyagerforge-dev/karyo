import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getAlertDeliveries,
  redeliverAlertDelivery,
  type AlertDeliveryDto,
  type AlertDeliveryStatus,
} from './monitors-api';

const DELIVERIES_KEY = ['alert-deliveries'];

/**
 * Row 34 (defect-burndown-4): the "Recent deliveries" panel data source. List (optionally
 * filtered by status) + a redeliver mutation for a stuck DEAD/FAILED row. Pattern mirrors
 * `useDeliveries`/`useRedeliver` in `features/webhooks/use-webhooks.ts`.
 */
export function useAlertDeliveries(status?: AlertDeliveryStatus) {
  return useQuery({
    queryKey: [...DELIVERIES_KEY, status ?? 'ALL'],
    queryFn: () => getAlertDeliveries(status),
    staleTime: 10_000,
  });
}

export function useRedeliverAlertDelivery() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => redeliverAlertDelivery(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DELIVERIES_KEY }),
  });
}

export type { AlertDeliveryDto, AlertDeliveryStatus };

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { getStreamingStatus, listStreamingOrders, retryStreamingOrder } from './streaming-api';
import type { StreamBucket } from '@/types/streaming';

/** Per-strategy streaming status. Polled every 10s -- the strategies board is a live dashboard. */
export function useStreamingStatus() {
  return useQuery({
    queryKey: ['streaming', 'status'],
    queryFn: getStreamingStatus,
    refetchInterval: 10_000,
  });
}

/** Orders in one bucket (WAITING/ESCALATED/STALLED). Polled every 10s, same reason as status. */
export function useStreamingOrders(bucket: StreamBucket) {
  return useQuery({
    queryKey: ['streaming', 'orders', bucket],
    queryFn: () => listStreamingOrders(bucket),
    refetchInterval: 10_000,
  });
}

function useInvalidateStreaming() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: ['streaming'] });
    void qc.invalidateQueries({ queryKey: ['orders'] });
  };
}

/** Retry a STALLED order -- re-queues it for release. `mutate` takes the order id. */
export function useRetryStreamingOrder() {
  const invalidate = useInvalidateStreaming();
  return useMutation({
    mutationFn: (id: number) => retryStreamingOrder(id),
    onSuccess: (order) => {
      invalidate();
      toast.success(`Order ${order.orderNumber} queued for retry`);
    },
  });
}

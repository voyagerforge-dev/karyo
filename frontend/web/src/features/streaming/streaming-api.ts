import { api } from '@/lib/api-client';
import type { StreamBucket, StreamOrder, StreamingStatusResponse } from '@/types/streaming';

/** GET /api/v1/streaming/status -- per-strategy streaming status + the global enabled flag. */
export const getStreamingStatus = () =>
  api.get<StreamingStatusResponse>('/api/v1/streaming/status');

/** GET /api/v1/streaming/orders -- candidate/queued orders in one bucket. */
export function listStreamingOrders(bucket: StreamBucket, size = 100) {
  const params = new URLSearchParams({ bucket, size: String(size) });
  return api.get<StreamOrder[]>(`/api/v1/streaming/orders?${params.toString()}`);
}

/** POST /api/v1/streaming/orders/{id}/retry -- re-queue a stalled order for release. */
export const retryStreamingOrder = (id: number) =>
  api.post<StreamOrder>(`/api/v1/streaming/orders/${id}/retry`, {});

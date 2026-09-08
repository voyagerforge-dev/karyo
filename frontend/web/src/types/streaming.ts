/**
 * Order streaming DTOs (v2.x Advanced Fulfillment pack).
 * Mirrors `com.karyo.streaming.dto.StreamingDtos` (services/streaming-service/karyo-streaming-api).
 */

/** Per-strategy streaming status row backing `GET /api/v1/streaming/status`. */
export interface StreamingStrategyStatus {
  strategyId: number;
  strategyName: string;
  releaseMode: string;
  timingStrategy: string;
  timingStrategyResolved: boolean;
  batchSize: number;
  maxWaitSeconds: number;
  abandonSeconds: number;
  eligible: number;
  waiting: number;
  escalated: number;
  stalled: number;
  pushFailed: number;
  lastFlushAt: string | null;
  batchesLastHour: number;
  releasedLastHour: number;
  pushedLastHour: number;
}

/** Envelope for `GET /api/v1/streaming/status`. */
export interface StreamingStatusResponse {
  enabled: boolean;
  strategies: StreamingStrategyStatus[];
}

/** `bucket` query param on `GET /api/v1/streaming/orders`. */
export type StreamBucket = 'WAITING' | 'ESCALATED' | 'STALLED' | 'PUSH_FAILED';

/** One streaming candidate/queued order row backing `GET /api/v1/streaming/orders`. */
export interface StreamOrder {
  orderId: number;
  orderNumber: string;
  customerName: string | null;
  prio: number;
  created: string;
  state: number;
  orderStrategyId: number | null;
  firstAttemptAt: string | null;
  escalatedAt: string | null;
  stalledAt: string | null;
  pendingLineCount: number;
  bucket: string;
}

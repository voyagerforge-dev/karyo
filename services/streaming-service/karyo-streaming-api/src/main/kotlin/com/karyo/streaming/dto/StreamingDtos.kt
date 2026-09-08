package com.karyo.streaming.dto

import java.time.Instant

/** Per-strategy streaming status row backing `GET /api/v1/streaming/status`. */
data class StreamingStrategyStatus(
    val strategyId: Long,
    val strategyName: String,
    val releaseMode: String,
    val timingStrategy: String,
    val timingStrategyResolved: Boolean,
    val batchSize: Int,
    val maxWaitSeconds: Int,
    val abandonSeconds: Int,
    val eligible: Long,
    val waiting: Long,
    val escalated: Long,
    val stalled: Long,
    val pushFailed: Long,
    val lastFlushAt: Instant?,
    val batchesLastHour: Long,
    val releasedLastHour: Long,
    val pushedLastHour: Long,
)

/** Envelope for `GET /api/v1/streaming/status`. */
data class StreamingStatusResponse(val enabled: Boolean, val strategies: List<StreamingStrategyStatus>)

/** One streaming candidate/queued order row backing `GET /api/v1/streaming/orders`. */
data class StreamOrderDto(
    val orderId: Long,
    val orderNumber: String,
    val customerName: String?,
    val prio: Int,
    val created: Instant,
    val state: Int,
    val orderStrategyId: Long?,
    val firstAttemptAt: Instant?,
    val escalatedAt: Instant?,
    val stalledAt: Instant?,
    val pendingLineCount: Int,
    val bucket: String,
)

/** Outbox payload for the `streaming.batch.flushed` event type. */
data class StreamBatchFlushedPayload(
    val batchId: Long,
    val strategyId: Long?,
    val trigger: String,
    val candidateCount: Int,
    val releasedCount: Int,
    val pushedCount: Int,
    val shortCount: Int,
    val retriedCount: Int,
    val stalledCount: Int,
    val flushedAt: Instant,
)

/** Outbox payload for the `streaming.order.escalated` event type. */
data class StreamOrderEscalatedPayload(
    val orderId: Long,
    val orderNumber: String,
    val strategyId: Long?,
    val firstAttemptAt: Instant?,
    val escalatedAt: Instant,
    val pendingLineCount: Int,
)

/** Outbox payload for the `streaming.order.stalled` event type. */
data class StreamOrderStalledPayload(
    val orderId: Long,
    val orderNumber: String,
    val strategyId: Long?,
    val firstAttemptAt: Instant?,
    val stalledAt: Instant,
    val reason: String,
)

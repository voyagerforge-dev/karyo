package com.karyo.streaming.spi

import java.time.Instant

/**
 * Decides WHEN a strategy's streaming candidates flush and HOW a short order escalates
 * (order streaming, B3). Key-selected through a CDI Instance like WaveSelectionStrategy; the
 * built-in is TimeSizeMicroBatch (key "time-size"). Implementations are pure: no I/O, no clock.
 */
interface ReleaseTimingStrategy {
    val key: String
    fun plan(ctx: FlushContext): FlushPlan
    fun escalate(ctx: ShortageContext): EscalationTier
}

data class FlushContext(
    val strategyId: Long,
    val clientId: Long,
    val now: Instant,
    val candidateCount: Long,
    val oldestCandidateCreated: Instant?,
    val batchSize: Int,
    val maxWaitSeconds: Int,
)

enum class FlushTrigger { SIZE, TIME, NONE }

data class FlushPlan(val release: Boolean, val limit: Int, val trigger: FlushTrigger) {
    companion object {
        val NONE = FlushPlan(release = false, limit = 0, trigger = FlushTrigger.NONE)
    }
}

data class ShortageContext(
    val orderId: Long,
    val clientId: Long,
    val now: Instant,
    val firstAttemptAt: Instant,
    val maxWaitSeconds: Int,
    val abandonSeconds: Int,
)

enum class EscalationTier { SILENT, ESCALATED, STALLED }

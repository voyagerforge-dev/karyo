package com.karyo.orders.spi

import java.time.Instant

/**
 * Streaming-side orchestration seam (order streaming, B3) -- the paid `karyo-streaming` engine's
 * entire surface into the FOSS orders module. Every method takes an explicit `clientId`: the only
 * caller is a scheduler thread, which never has an ambient `TenantContext` to read (scheduler
 * doctrine). Mirrors [OrderReleasePort], the wave module's equivalent seam.
 *
 * Nothing paid lives behind this interface: the impl is written entirely against existing
 * tenant-scoped repositories and `OrderService`'s two sanctioned release hops.
 */
interface StreamingReleasePort {
    /**
     * Every strategy with its parsed streaming config. The scheduler iterates ALL of them, not
     * just the STREAM ones: an override-STREAM order can sit on a MANUAL strategy.
     */
    fun strategiesWithStreamingConfig(): List<StreamingStrategyView>

    /**
     * Distinct client ids with at least one streaming candidate or in-flight retryable --
     * deliberately unscoped (the tenant loop the scheduler sweeps), same native-query shape as
     * [OrderReleasePort.clientIdsWithWaveEligibleOrders].
     */
    fun clientIdsWithStreamingWork(streamStrategyIds: Set<Long>, defaultIsStream: Boolean): List<Long>

    /** Count + oldest `created` of the candidate pool: the timing strategy's size/age inputs. */
    fun streamCandidateSummary(scope: StreamScope, clientId: Long): StreamCandidateSummary

    /** CREATED, un-waved, un-stalled, effective mode STREAM; ordered prio DESC, created ASC. */
    fun findStreamCandidates(scope: StreamScope, clientId: Long, limit: Int): List<StreamOrderView>

    /**
     * RELEASED, already attempted, un-stalled, with at least one PENDING line; same ordering.
     * Escalated (tier 2) orders are still retryable -- only a stall takes an order out.
     */
    fun findStreamRetryable(scope: StreamScope, clientId: Long, limit: Int): List<StreamOrderView>

    /** Write-once stamp of the first streaming attempt (the clock every escalation tier counts from). */
    fun markStreamAttempt(orderId: Long, clientId: Long, at: Instant)

    /** Write-once tier-2 stamp, so the `streaming.order.escalated` event fires exactly once. */
    fun markStreamEscalated(orderId: Long, clientId: Long, at: Instant)

    /** Write-once tier-3 stamp; also back-fills the attempt stamp when streaming stalls on its first touch. */
    fun markStreamStalled(orderId: Long, clientId: Long, at: Instant)

    /**
     * Retry endpoint: clears the stalled and escalated stamps and restarts the clock
     * (`firstAttemptAt = now`). Throws `OrderException.NotFound` out of scope and
     * [IllegalStateException] when the order is not stalled.
     */
    fun resetStreamStall(orderId: Long, clientId: Long, now: Instant): StreamOrderView

    /**
     * Re-reads the order inside its own transaction and SKIPS anything no longer CREATED (spec
     * ruling 12: manual release wins the race); otherwise runs the existing `OrderService.release`.
     */
    fun releaseForStreaming(orderId: Long, clientId: Long): StreamReleaseOutcome

    /** Same re-read guard against RELEASED; otherwise the existing `OrderService.retryReservation`. */
    fun retryForStreaming(orderId: Long, clientId: Long): StreamReleaseOutcome

    /** The status endpoint's five numbers for one strategy scope. */
    fun streamOrderCounts(scope: StreamScope, clientId: Long): StreamOrderCounts

    /** The screen's bucket lists, oldest attempt first. Not strategy-scoped: the board is per tenant. */
    fun findStreamOrders(clientId: Long, bucket: StreamBucket, limit: Int): List<StreamOrderView>

    /** Single-order view, tenant-scoped; null out of scope. */
    fun findStreamOrder(orderId: Long, clientId: Long): StreamOrderView?

    /**
     * Stamped in-flight orders no current configuration can reach: RELEASED, stream-stamped,
     * un-stalled, and either not effectively STREAM any more (strategy switched off STREAM and
     * no per-order override) or pointing at a strategy row that no longer exists. [currentScopes]
     * is one scope per CURRENTLY EXISTING strategy with its live [StreamScope.strategyIsStream]
     * flag. The scheduler stalls what this returns (D3, defect-burndown-7); stamps are never
     * cleared (stamps-outlive-mode-changes ruling).
     */
    fun findStreamOrphans(currentScopes: List<StreamScope>, clientId: Long, limit: Int): List<StreamOrderView>
}

/**
 * One strategy's slice of the candidate space. [strategyId] is the concrete strategy row;
 * [isDefault] means that row is the DEFAULT strategy, so orders with a null `orderStrategyId`
 * belong to it too; [strategyIsStream] is whether the strategy's own `releaseMode` is STREAM,
 * which decides whether an order with no override inherits STREAM.
 */
data class StreamScope(val strategyId: Long, val isDefault: Boolean, val strategyIsStream: Boolean)

data class StreamingStrategyView(
    val strategyId: Long,
    val name: String,
    val isDefault: Boolean,
    val config: StreamingStrategyConfig,
)

data class StreamCandidateSummary(val count: Long, val oldestCreated: Instant?)

enum class StreamReleaseResult { SKIPPED, PROCESSABLE, RELEASED_SHORT }

data class StreamReleaseOutcome(val result: StreamReleaseResult, val pendingLineCount: Int)

data class StreamOrderCounts(
    val eligible: Long,
    val waiting: Long,
    val escalated: Long,
    val stalled: Long,
    val pushFailed: Long,
)

enum class StreamBucket { WAITING, ESCALATED, STALLED, PUSH_FAILED }

data class StreamOrderView(
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
)

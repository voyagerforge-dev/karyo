package com.karyo.orders.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.OrderStrategy
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.repository.OrderStrategyRepository
import com.karyo.orders.service.OrderService
import com.karyo.orders.spi.StreamBucket
import com.karyo.orders.spi.StreamCandidateSummary
import com.karyo.orders.spi.StreamOrderCounts
import com.karyo.orders.spi.StreamOrderView
import com.karyo.orders.spi.StreamReleaseOutcome
import com.karyo.orders.spi.StreamReleaseResult
import com.karyo.orders.spi.StreamScope
import com.karyo.orders.spi.StreamingReleasePort
import com.karyo.orders.spi.StreamingStrategyView
import com.karyo.orders.vo.OrderState
import com.karyo.orders.vo.ReleaseMode
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Order streaming (B3) seam over orders-core. Explicit `clientId` everywhere, no ambient
 * `TenantContext` read anywhere in this class; every entity read is `@Transactional` for the same
 * back-to-back-call reason documented at length on [DefaultOrderReleasePort] (a non-HTTP caller
 * issuing two port calls in a row otherwise shares ONE ad hoc persistence context, so the second
 * read can return a session-cached, pre-mutation instance). The one method without the annotation
 * is [clientIdsWithStreamingWork], a native scalar query that loads no entity at all and so has no
 * persistence context to be stale -- the same carve-out
 * `DefaultOrderReleasePort.clientIdsWithWaveEligibleOrders` has. The paid scheduler in
 * `karyo-streaming-core` is the only caller.
 *
 * No streaming state lives here: the three stamps are plain columns on [DeliveryOrder] and the
 * two write paths delegate to [OrderService]'s existing release/retry chokepoints, exactly as
 * [DefaultOrderReleasePort] does for waves.
 */
@ApplicationScoped
class DefaultStreamingReleasePort(
    private val orderRepository: DeliveryOrderRepository,
    private val strategyRepository: OrderStrategyRepository,
    private val orderService: OrderService,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager,
) : StreamingReleasePort {

    @Transactional
    override fun strategiesWithStreamingConfig(): List<StreamingStrategyView> =
        strategyRepository.listAll().map { strategy ->
            StreamingStrategyView(
                strategyId = strategy.id!!,
                name = strategy.name,
                isDefault = strategy.name == OrderStrategy.DEFAULT_NAME,
                config = StreamingConfigParser.parse(strategy.extensionProperties, objectMapper),
            )
        }

    /**
     * Deliberately unscoped native query (the tenant loop), same shape as
     * `DeliveryOrderRepository.clientIdsWithWaveEligibleOrders`. The strategy ids are inlined
     * rather than bound because they are `Long`s the caller derived from
     * [strategiesWithStreamingConfig], never caller-supplied text.
     *
     * Deliberately NOT `@Transactional`: it projects scalar client ids, never a managed entity, so
     * the stale-session hazard the other reads guard against cannot arise here.
     */
    @Suppress("UNCHECKED_CAST")
    override fun clientIdsWithStreamingWork(streamStrategyIds: Set<Long>, defaultIsStream: Boolean): List<Long> {
        val sql = StreamingQueries.clientIdsSql(streamStrategyIds, defaultIsStream)
        return (entityManager.createNativeQuery(sql).resultList as List<Any>).map { (it as Number).toLong() }
    }

    @Transactional
    override fun streamCandidateSummary(scope: StreamScope, clientId: Long): StreamCandidateSummary {
        val where = StreamingQueries.candidateWhere(scope)
        val count = orderRepository.count(where, StreamingQueries.candidateParams(scope, clientId))
        val oldest = orderRepository
            .find(where, Sort.ascending("created"), StreamingQueries.candidateParams(scope, clientId))
            .page(Page.of(0, 1))
            .firstResult()
            ?.created
        return StreamCandidateSummary(count, oldest)
    }

    @Transactional
    override fun findStreamCandidates(scope: StreamScope, clientId: Long, limit: Int): List<StreamOrderView> =
        orderRepository
            .find(
                StreamingQueries.candidateWhere(scope),
                StreamingQueries.STREAM_ORDERING,
                StreamingQueries.candidateParams(scope, clientId),
            )
            .page(Page.of(0, limit))
            .list()
            .let(::toViews)

    @Transactional
    override fun findStreamRetryable(scope: StreamScope, clientId: Long, limit: Int): List<StreamOrderView> =
        orderRepository
            .find(
                StreamingQueries.retryableWhere(scope),
                StreamingQueries.STREAM_ORDERING,
                Parameters.with("clientId", clientId)
                    .and("released", OrderState.RELEASED.code)
                    .and("pending", OrderState.PENDING.code)
                    .and("strategyId", scope.strategyId),
            )
            .page(Page.of(0, limit))
            .list()
            .let(::toViews)

    @Transactional
    override fun markStreamAttempt(orderId: Long, clientId: Long, at: Instant) {
        val order = load(orderId, clientId)
        if (order.streamFirstAttemptAt == null) order.streamFirstAttemptAt = at
    }

    @Transactional
    override fun markStreamEscalated(orderId: Long, clientId: Long, at: Instant) {
        val order = load(orderId, clientId)
        if (order.streamEscalatedAt == null) order.streamEscalatedAt = at
    }

    @Transactional
    override fun markStreamStalled(orderId: Long, clientId: Long, at: Instant) {
        val order = load(orderId, clientId)
        if (order.streamFirstAttemptAt == null) order.streamFirstAttemptAt = at
        if (order.streamStalledAt == null) order.streamStalledAt = at
    }

    @Transactional
    override fun resetStreamStall(orderId: Long, clientId: Long, now: Instant): StreamOrderView {
        val order = load(orderId, clientId)
        check(order.streamStalledAt != null) { "DeliveryOrder $orderId is not stalled" }
        check(order.state == OrderState.CREATED.code || order.state == OrderState.RELEASED.code) {
            "DeliveryOrder $orderId is not retryable in state ${order.state}"
        }
        order.streamStalledAt = null
        order.streamEscalatedAt = null
        order.streamFirstAttemptAt = now
        return toView(order)
    }

    @Transactional
    override fun releaseForStreaming(orderId: Long, clientId: Long): StreamReleaseOutcome {
        val order = load(orderId, clientId)
        if (order.state != OrderState.CREATED.code) return StreamReleaseOutcome(StreamReleaseResult.SKIPPED, 0)
        orderService.release(orderId, clientId)
        return outcomeOf(load(orderId, clientId))
    }

    @Transactional
    override fun retryForStreaming(orderId: Long, clientId: Long): StreamReleaseOutcome {
        val order = load(orderId, clientId)
        if (order.state != OrderState.RELEASED.code) return StreamReleaseOutcome(StreamReleaseResult.SKIPPED, 0)
        orderService.retryReservation(orderId, clientId)
        return outcomeOf(load(orderId, clientId))
    }

    @Transactional
    override fun streamOrderCounts(scope: StreamScope, clientId: Long): StreamOrderCounts = StreamOrderCounts(
        eligible = orderRepository.count(
            StreamingQueries.candidateWhere(scope),
            StreamingQueries.candidateParams(scope, clientId),
        ),
        waiting = orderRepository.count(
            StreamingQueries.bucketWhere(StreamBucket.WAITING, scope),
            StreamingQueries.bucketParams(StreamBucket.WAITING, clientId, scope.strategyId),
        ),
        escalated = orderRepository.count(
            StreamingQueries.bucketWhere(StreamBucket.ESCALATED, scope),
            StreamingQueries.bucketParams(StreamBucket.ESCALATED, clientId, scope.strategyId),
        ),
        stalled = orderRepository.count(
            StreamingQueries.bucketWhere(StreamBucket.STALLED, scope),
            StreamingQueries.bucketParams(StreamBucket.STALLED, clientId, scope.strategyId),
        ),
        pushFailed = orderRepository.count(
            StreamingQueries.bucketWhere(StreamBucket.PUSH_FAILED, scope),
            StreamingQueries.bucketParams(StreamBucket.PUSH_FAILED, clientId, scope.strategyId),
        ),
    )

    @Transactional
    override fun findStreamOrders(clientId: Long, bucket: StreamBucket, limit: Int): List<StreamOrderView> =
        orderRepository
            .find(
                StreamingQueries.bucketWhere(bucket, scope = null),
                Sort.ascending("streamFirstAttemptAt"),
                StreamingQueries.bucketParams(bucket, clientId, strategyId = null),
            )
            .page(Page.of(0, limit))
            .list()
            .let(::toViews)

    @Transactional
    override fun findStreamOrder(orderId: Long, clientId: Long): StreamOrderView? =
        orderRepository.findByIdAndClient(orderId, clientId)?.let(::toView)

    @Transactional
    override fun findStreamOrphans(currentScopes: List<StreamScope>, clientId: Long, limit: Int): List<StreamOrderView> =
        orderRepository
            .find(
                StreamingQueries.orphanWhere(currentScopes),
                Sort.ascending("streamFirstAttemptAt"),
                Parameters.with("clientId", clientId)
                    .and("created", OrderState.CREATED.code)
                    .and("released", OrderState.RELEASED.code),
            )
            .page(Page.of(0, limit))
            .list()
            .let(::toViews)

    private fun load(orderId: Long, clientId: Long): DeliveryOrder =
        orderRepository.findByIdAndClient(orderId, clientId)
            ?: throw OrderException.NotFound("DeliveryOrder", orderId)

    /**
     * PROCESSABLE only when the order actually reached PROCESSABLE with nothing left PENDING;
     * anything else after a successful `release`/`retryReservation` is a partial reservation the
     * caller must keep retrying (spec ruling 7).
     */
    private fun outcomeOf(order: DeliveryOrder): StreamReleaseOutcome {
        val pending = order.lines.count { it.state == OrderState.PENDING.code }
        val result = if (order.state == OrderState.PROCESSABLE.code && pending == 0) {
            StreamReleaseResult.PROCESSABLE
        } else {
            StreamReleaseResult.RELEASED_SHORT
        }
        return StreamReleaseOutcome(result, pending)
    }

    /** One grouped query per result page instead of one lazy `lines` select per row (D8). */
    private fun pendingLineCounts(orders: List<DeliveryOrder>): Map<Long, Long> {
        if (orders.isEmpty()) return emptyMap()
        val rows = entityManager.createQuery(
            "select l.deliveryOrder.id, count(l.id) from DeliveryOrderLine l " +
                "where l.deliveryOrder.id in :ids and l.state = :pending group by l.deliveryOrder.id",
            Array<Any>::class.java,
        )
            .setParameter("ids", orders.map { it.id!! })
            .setParameter("pending", OrderState.PENDING.code)
            .resultList
        return rows.associate { (it[0] as Long) to (it[1] as Long) }
    }

    private fun toViews(orders: List<DeliveryOrder>): List<StreamOrderView> {
        val counts = pendingLineCounts(orders)
        return orders.map { toView(it, (counts[it.id] ?: 0L).toInt()) }
    }

    private fun toView(order: DeliveryOrder, pendingLineCount: Int) = StreamOrderView(
        orderId = order.id!!,
        orderNumber = order.orderNumber,
        customerName = order.customerName,
        prio = order.prio,
        created = order.created,
        state = order.state,
        orderStrategyId = order.orderStrategyId,
        firstAttemptAt = order.streamFirstAttemptAt,
        escalatedAt = order.streamEscalatedAt,
        stalledAt = order.streamStalledAt,
        pendingLineCount = pendingLineCount,
    )

    private fun toView(order: DeliveryOrder) =
        toView(order, order.lines.count { it.state == OrderState.PENDING.code })
}

/**
 * The JPQL/SQL fragments [DefaultStreamingReleasePort] builds, extracted so the port class stays a
 * thin set of delegations. Every [Parameters] set is built per query: Panache binds every entry it
 * is given, and Hibernate rejects a bound parameter the query does not mention, so a single shared
 * parameter bag across differently-shaped queries would fail at runtime.
 */
internal object StreamingQueries {

    /** prio DESC, created ASC -- the release order every streaming query uses. */
    val STREAM_ORDERING: Sort = Sort.descending("prio").and("created", Sort.Direction.Ascending)

    /**
     * `(orderStrategyId = :strategyId [or orderStrategyId is null])` -- a null `orderStrategyId`
     * means the DEFAULT strategy. [prefix] qualifies the attribute for the aliased long-form query.
     */
    private fun strategyClause(scope: StreamScope, prefix: String = ""): String =
        if (scope.isDefault) {
            "(${prefix}orderStrategyId = :strategyId or ${prefix}orderStrategyId is null)"
        } else {
            "${prefix}orderStrategyId = :strategyId"
        }

    fun candidateWhere(scope: StreamScope): String {
        val modeClause = if (scope.strategyIsStream) {
            "(releaseModeOverride = '${ReleaseMode.STREAM.name}' or releaseModeOverride is null)"
        } else {
            "releaseModeOverride = '${ReleaseMode.STREAM.name}'"
        }
        return "clientId = :clientId and state = :state and waveId is null and streamStalledAt is null " +
            "and $modeClause and ${strategyClause(scope)}"
    }

    fun candidateParams(scope: StreamScope, clientId: Long): Parameters =
        Parameters.with("clientId", clientId)
            .and("state", OrderState.CREATED.code)
            .and("strategyId", scope.strategyId)

    /**
     * The long `from DeliveryOrder o where ...` form: the "has at least one PENDING line" test is
     * a correlated `exists` subquery, which needs an alias for the outer root that Panache's
     * short form never introduces.
     *
     * D3 (defect-burndown-7): narrowed to the strategy's CURRENT effective mode. Before this an
     * order that had already been stamped kept matching regardless of whether the strategy still
     * carried `releaseMode = STREAM`, so switching a strategy off STREAM never stopped its
     * in-flight orders from retrying forever -- only a per-order override now survives a mode
     * switch on a non-STREAM strategy.
     */
    fun retryableWhere(scope: StreamScope): String {
        val modeClause = if (scope.strategyIsStream) {
            "(o.releaseModeOverride = '${ReleaseMode.STREAM.name}' or o.releaseModeOverride is null)"
        } else {
            "o.releaseModeOverride = '${ReleaseMode.STREAM.name}'"
        }
        return "from DeliveryOrder o where o.clientId = :clientId and o.state = :released " +
            "and o.streamFirstAttemptAt is not null and o.streamStalledAt is null " +
            "and $modeClause " +
            "and ${strategyClause(scope, prefix = "o.")} " +
            "and exists (select l.id from DeliveryOrderLine l where l.deliveryOrder = o and l.state = :pending)"
    }

    /**
     * D3 (defect-burndown-7): stamped RELEASED un-stalled orders NOT reachable by the retry pass:
     * not effectively STREAM (override wins, else the strategy's live mode) OR the strategy row is
     * gone. Ids are inlined, never caller-supplied text (clientIdsSql precedent).
     *
     * The `releaseModeOverride = 'STREAM'` branch is guarded by an explicit `is not null` rather
     * than relying on `... or releaseModeOverride is null` the way [candidateWhere]/[retryableWhere]
     * do: this predicate sits inside a `not (...)`, and three-valued SQL logic makes
     * `NULL or FALSE` evaluate to `NULL` (not `FALSE`) whenever an order has no override AND the
     * strategy is not in the STREAM set -- `not (NULL)` is `NULL`, which `WHERE` silently drops the
     * row instead of matching it. `is not null and = 'STREAM'` collapses deterministically to
     * `FALSE` for a null override, so the outer boolean algebra never touches an unknown value.
     *
     * D-final-1 (defect-burndown-7 whole-branch review): the state clause covers BOTH in-flight
     * states, `state in (:created, :released)`, not just RELEASED. A CREATED order can be
     * stamped-and-unstalled too: a hard release failure at flush stalls it while still CREATED,
     * and the Retry endpoint accepts CREATED (it un-stalls without transitioning state). If its
     * strategy is switched off STREAM in that window, the order is stamped + un-stalled + not
     * effectively STREAM but sitting in CREATED -- `retryableWhere` only ever looks at RELEASED, so
     * without this CREATED branch the order matches no query at all (not a fresh candidate, not
     * retryable, not an orphan) and silently stops being swept forever.
     */
    fun orphanWhere(currentScopes: List<StreamScope>): String {
        val allIds = currentScopes.map { it.strategyId }
        val streamIds = currentScopes.filter { it.strategyIsStream }.map { it.strategyId }
        val defaultExists = currentScopes.any { it.isDefault }
        val defaultIsStream = currentScopes.any { it.isDefault && it.strategyIsStream }
        val existsClause = strategySetClause(allIds, defaultExists)
        val streamClause = strategySetClause(streamIds, defaultIsStream)
        val effectiveStream =
            "((releaseModeOverride is not null and releaseModeOverride = '${ReleaseMode.STREAM.name}') " +
                "or (releaseModeOverride is null and $streamClause))"
        return "clientId = :clientId and state in (:created, :released) " +
            "and streamFirstAttemptAt is not null and streamStalledAt is null " +
            "and not ($effectiveStream and $existsClause)"
    }

    /**
     * `(orderStrategyId in (..) [or orderStrategyId is null])`, or a false literal when empty.
     *
     * The `IN` branch is guarded by an explicit `is not null` for the same three-valued-logic
     * reason [orphanWhere]'s `effectiveStream` is: `NULL in (1, 2)` evaluates to SQL `NULL`, not
     * `FALSE`. Without the guard, a null-`orderStrategyId` order fed through this clause with
     * `includeNull = false` (the DEFAULT strategy absent from the current scan, e.g. deleted or
     * renamed) would poison `orphanWhere`'s `not (... and existsClause)` to `NULL`, which `WHERE`
     * silently drops instead of matching -- exactly the "strategy row is gone" orphan this
     * function exists to catch.
     */
    private fun strategySetClause(ids: List<Long>, includeNull: Boolean): String {
        val parts = mutableListOf<String>()
        if (ids.isNotEmpty()) parts.add("(orderStrategyId is not null and orderStrategyId in (${ids.joinToString()}))")
        if (includeNull) parts.add("orderStrategyId is null")
        return if (parts.isEmpty()) "(1 = 0)" else "(${parts.joinToString(" or ")})"
    }

    /** [scope] null = the whole tenant (the screen's buckets are not strategy-scoped). */
    fun bucketWhere(bucket: StreamBucket, scope: StreamScope?): String {
        val base = StringBuilder("clientId = :clientId and streamFirstAttemptAt is not null")
        if (scope != null) base.append(" and ").append(strategyClause(scope))
        return base.append(
            when (bucket) {
                StreamBucket.WAITING ->
                    " and state = :released and streamEscalatedAt is null and streamStalledAt is null"
                StreamBucket.ESCALATED ->
                    " and state = :released and streamEscalatedAt is not null and streamStalledAt is null"
                StreamBucket.STALLED ->
                    " and state in (:created, :released) and streamStalledAt is not null"
                StreamBucket.PUSH_FAILED ->
                    " and state = :processable and streamStalledAt is null"
            },
        ).toString()
    }

    fun bucketParams(bucket: StreamBucket, clientId: Long, strategyId: Long?): Parameters {
        val params = Parameters.with("clientId", clientId)
        if (strategyId != null) params.and("strategyId", strategyId)
        when (bucket) {
            StreamBucket.WAITING, StreamBucket.ESCALATED -> params.and("released", OrderState.RELEASED.code)
            StreamBucket.STALLED ->
                params.and("released", OrderState.RELEASED.code).and("created", OrderState.CREATED.code)
            StreamBucket.PUSH_FAILED -> params.and("processable", OrderState.PROCESSABLE.code)
        }
        return params
    }

    fun clientIdsSql(streamStrategyIds: Set<Long>, defaultIsStream: Boolean): String {
        val ids = if (streamStrategyIds.isEmpty()) "-1" else streamStrategyIds.joinToString(",")
        val strategyClause = StringBuilder("order_strategy_id IN ($ids)")
        if (defaultIsStream) strategyClause.append(" OR order_strategy_id IS NULL")
        return """
            SELECT DISTINCT client_id FROM karyo.delivery_orders
            WHERE wave_id IS NULL AND stream_stalled_at IS NULL AND (
              (state = ${OrderState.CREATED.code} AND (release_mode_override = '${ReleaseMode.STREAM.name}'
                 OR (release_mode_override IS NULL AND ($strategyClause))))
              OR (state = ${OrderState.RELEASED.code} AND stream_first_attempt_at IS NOT NULL))
        """.trimIndent()
    }
}

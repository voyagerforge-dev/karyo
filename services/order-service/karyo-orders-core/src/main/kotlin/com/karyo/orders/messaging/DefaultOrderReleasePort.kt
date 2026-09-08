package com.karyo.orders.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.OrderStrategy
import com.karyo.orders.repository.DeliveryOrderLineRepository
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.repository.OrderStrategyRepository
import com.karyo.orders.service.OrderService
import com.karyo.orders.spi.OrderReleasePort
import com.karyo.orders.spi.ShortageView
import com.karyo.orders.spi.WaveOrderView
import com.karyo.orders.spi.WaveReleaseOutcome
import com.karyo.orders.spi.WaveStrategyConfig
import com.karyo.orders.spi.WaveStrategyView
import com.karyo.orders.vo.OrderState
import com.karyo.orders.vo.ReleaseMode
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Default [OrderReleasePort] impl: the wave module's whole orchestration surface into orders,
 * written entirely against existing tenant-scoped repositories and [OrderService] -- no wave-side
 * state lives here. Mirrors [com.karyo.orders.service.DefaultOrderProgressionPort]'s "thin
 * delegate to OrderService" shape for the two write paths ([releaseForWave],
 * [releaseLineReservations]) that must route through the same chokepoints the REST lifecycle uses.
 *
 * Every method here reaches the tenant through an explicit `clientId` -- no ambient
 * `TenantContext` read anywhere in this class (the "scheduler doctrine" the interface KDoc
 * promises, genuinely end to end). [releaseForWave]/[releaseLineReservations] used to need a
 * `TenantContext`-priming workaround here, because `OrderService`'s reservation math bottomed out
 * in `StockReserver`'s AMBIENT-only bulk `reserve`/`release` overloads (Task 3 review
 * IMPORTANT-2). That workaround mutated the live `@RequestScoped` bean with no restore -- exactly
 * the pattern defect-burndown-4 row 30 removed from `MonitorEvaluator` -- and was itself unsafe
 * on the very path ([releaseForWave]) that deliberately lets exceptions escape uncaught for
 * HOLD_WAVE rollback, since a thrown exception would skip any restore this class might have
 * added. Fixed at the root instead: `StockReserver.reserve(request, clientId)` /
 * `release(reservations, clientId)` explicit-`clientId` overloads were added to
 * `karyo-inventory-api` (mirroring the existing `reserveOnStockUnit`/single-slice `release`
 * overloads' shape), `DefaultStockReserver` implements them via the same `TenantContext.ownerScoped(clientId)`
 * synthetic-context technique those overloads already used, and `OrderService.reserveLine`/
 * `cancel`/`releaseLineReservations` now call the explicit overloads with the `clientId` they
 * already carry as a parameter. No priming, no restore, no live-bean mutation -- this port is a
 * plain delegate again.
 *
 * [findWaveEligible]/[memberViews]/[waveIdOf] are explicitly `@Transactional` reads (not the
 * default un-annotated Panache read), found necessary while writing this class's own IT: two
 * `port.*` calls issued back-to-back from a single non-HTTP caller (a direct bean call, exactly
 * how the wave scheduler behind this port and this class's own test both invoke it) can otherwise
 * share ONE ad hoc, non-transactional `EntityManager`/persistence context for the whole call
 * chain -- so a SECOND read of the same order id can return a session-cached, pre-mutation
 * instance instead of re-querying, even though an intervening `@Transactional` write (its own,
 * fresh transaction-scoped persistence context) already committed the change to the database. A
 * plain REST caller never hits this (each HTTP request gets its own fresh request-scoped
 * `EntityManager`), which is why the symptom only showed up here, not on any existing
 * REST-fronted repository read in this module.
 */
@ApplicationScoped
class DefaultOrderReleasePort(
    private val orderRepository: DeliveryOrderRepository,
    private val lineRepository: DeliveryOrderLineRepository,
    private val strategyRepository: OrderStrategyRepository,
    private val orderService: OrderService,
    private val objectMapper: ObjectMapper,
) : OrderReleasePort {

    @Transactional
    override fun findWaveEligible(strategyId: Long?, clientId: Long, limit: Int): List<WaveOrderView> {
        val query = StringBuilder("clientId = :clientId and state = :state and waveId is null")
        val params = Parameters.with("clientId", clientId).and("state", OrderState.CREATED.code)
        if (strategyId != null) {
            query.append(" and orderStrategyId = :strategyId")
            params.and("strategyId", strategyId)
        }
        appendStreamExclusion(query, params)
        return orderRepository
            .find(query.toString(), Sort.ascending("created"), params)
            .page(Page.of(0, limit))
            .list()
            .map(::toWaveOrderView)
    }

    /**
     * Refuses (per the interface contract) if ANY named order is not CREATED or already waved --
     * checked for the whole batch BEFORE any row is stamped, so a partially-eligible batch never
     * leaves a partial wave assignment behind. An unknown/foreign order id is treated the same as
     * "not eligible" (it is, definitionally, not a CREATED un-waved order this tenant owns).
     */
    @Transactional
    override fun assignToWave(orderIds: List<Long>, waveId: Long, clientId: Long) {
        if (orderIds.isEmpty()) return
        val orders = orderIds.map { id ->
            orderRepository.findByIdAndClient(id, clientId)
                ?: throw IllegalStateException("Order $id not found for client $clientId")
        }
        orders.forEach { order ->
            if (
                order.state != OrderState.CREATED.code || order.waveId != null ||
                order.streamFirstAttemptAt != null || order.releaseModeOverride == ReleaseMode.STREAM.name
            ) {
                throw IllegalStateException(
                    "Order ${order.id} is not eligible for wave assignment " +
                        "(state=${order.state}, waveId=${order.waveId}, " +
                        "streamFirstAttemptAt=${order.streamFirstAttemptAt}, " +
                        "releaseModeOverride=${order.releaseModeOverride})"
                )
            }
        }
        orders.forEach { it.waveId = waveId }
    }

    /** Idempotent: an order not found (or already un-waved) is silently skipped. */
    @Transactional
    override fun clearWave(orderIds: List<Long>, clientId: Long) {
        orderIds.mapNotNull { id -> orderRepository.findByIdAndClient(id, clientId) }
            .forEach { it.waveId = null }
    }

    @Transactional
    override fun memberViews(waveId: Long, clientId: Long): List<WaveOrderView> =
        orderRepository
            .find("clientId = ?1 and waveId = ?2", Sort.ascending("created"), clientId, waveId)
            .list()
            .map(::toWaveOrderView)

    @Transactional
    override fun waveIdOf(orderId: Long, clientId: Long): Long? =
        orderRepository.findByIdAndClient(orderId, clientId)?.waveId

    /** One JPQL projection over the wave's member lines, replacing the sort station's per-member
     *  [com.karyo.orders.spi.DeliveryOrderLookup.findForPicking] loop. `@Transactional` for the
     *  same reason [memberViews] is: direct (non-REST) bean callers otherwise share one ad hoc
     *  persistence context across the whole call chain. */
    @Transactional
    override fun memberLineOwners(waveId: Long, clientId: Long): Map<Long, Long> =
        lineRepository.lineOwnersByWave(waveId, clientId)

    override fun releaseForWave(orderId: Long, clientId: Long): WaveReleaseOutcome {
        val response = orderService.release(orderId, clientId)
        return WaveReleaseOutcome(
            orderId = orderId,
            shortages = response.shortages.map { shortage ->
                ShortageView(
                    orderId = orderId,
                    lineId = shortage.lineId,
                    itemDataId = shortage.itemDataId,
                    itemDataNumber = shortage.itemDataNumber,
                    requested = shortage.requestedAmount,
                    reserved = shortage.reservedAmount,
                )
            },
        )
    }

    override fun releaseLineReservations(lineIds: List<Long>, clientId: Long) {
        orderService.releaseLineReservations(lineIds, clientId)
    }

    /** Excludes STREAM strategies (spec ruling 5): a STREAM strategy is never auto-waved, even
     *  with `waveAutoRelease = true` set alongside it. */
    override fun waveEnabledStrategies(): List<WaveStrategyView> =
        strategyRepository.listAll()
            .map { Triple(it, parseConfig(it.extensionProperties), StreamingConfigParser.parse(it.extensionProperties, objectMapper)) }
            .filter { (_, waveCfg, streamCfg) -> waveCfg.waveAutoRelease && streamCfg.releaseMode != ReleaseMode.STREAM }
            .map { (strategy, waveCfg, _) -> WaveStrategyView(strategy.id!!, strategy.name, waveCfg) }

    override fun waveConfig(strategyId: Long?): WaveStrategyConfig {
        val strategy = strategyId?.let { strategyRepository.findById(it) } ?: return WaveStrategyConfig()
        return parseConfig(strategy.extensionProperties)
    }

    @Transactional
    override fun lineIdsOf(orderId: Long, clientId: Long): List<Long> =
        orderRepository.findByIdAndClient(orderId, clientId)?.lines?.mapNotNull { it.id } ?: emptyList()

    /** Same tenant-scoped `clientId = ?1 and waveId = ?2` query [memberViews] uses, further
     *  restricted to orders whose `state >= RELEASED` -- a wave-member order that release has
     *  never touched (still CREATED, `reservedAmount` still its zero default on every line) is
     *  NOT a shortage; `line.shortage` is `amount - reservedAmount`, which would otherwise flag
     *  every single line of a PLANNED wave as "short" before release ever runs (review
     *  IMPORTANT-1). Only an order [releaseForWave] actually attempted reservation for can
     *  meaningfully be short. */
    @Transactional
    override fun shortagesOf(waveId: Long, clientId: Long): List<ShortageView> =
        orderRepository.find(
            "clientId = ?1 and waveId = ?2 and state >= ?3",
            clientId,
            waveId,
            OrderState.RELEASED.code,
        ).list()
            .flatMap { order ->
                order.lines.filter { it.shortage.signum() > 0 }.map { line ->
                    ShortageView(
                        orderId = order.id!!,
                        lineId = line.id!!,
                        itemDataId = line.itemDataId,
                        itemDataNumber = line.itemDataNumber,
                        requested = line.amount,
                        reserved = line.reservedAmount,
                    )
                }
            }

    override fun clientIdsWithWaveEligibleOrders(): List<Long> = orderRepository.clientIdsWithWaveEligibleOrders()

    /** `order_strategies` is system-level (BaseEntity, no clientId) and small, so a full
     *  `listAll` + per-row JSON parse is proportionate (same table `waveEnabledStrategies`
     *  already scans in full). Used by [com.karyo.wave.service.WaveSelectionRuleService]'s
     *  delete guard (409 when non-empty) and `boundByStrategies` response field. */
    override fun strategiesBindingRule(ruleId: Long): List<Long> =
        strategyRepository.listAll()
            .filter { parseConfig(it.extensionProperties).waveSelectionRuleId == ruleId }
            .map { it.id!! }

    /** Ids of strategies whose releaseMode is STREAM (+ whether the DEFAULT strategy is). Spec ruling 5. */
    private fun streamStrategies(): Pair<Set<Long>, Boolean> {
        val all = strategyRepository.listAll()
        val stream = all.filter { StreamingConfigParser.parse(it.extensionProperties, objectMapper).releaseMode == ReleaseMode.STREAM }
        val defaultIsStream = stream.any { it.name == OrderStrategy.DEFAULT_NAME }
        return stream.map { it.id!! }.toSet() to defaultIsStream
    }

    /** JPQL fragment excluding orders the streaming engine owns or will own (spec ruling 5). */
    private fun appendStreamExclusion(query: StringBuilder, params: Parameters) {
        query.append(" and streamFirstAttemptAt is null and (releaseModeOverride is null or releaseModeOverride <> 'STREAM')")
        val (streamIds, defaultIsStream) = streamStrategies()
        if (streamIds.isEmpty()) return
        // Two live cases, and only two: `defaultIsStream` is derived from `streamIds`, so it can
        // never be true while the set is empty. Either the DEFAULT strategy is itself one of the
        // STREAM ones, and then an order with a null orderStrategyId inherits STREAM and has to go
        // too; or it is not, and a null orderStrategyId inherits the DEFAULT strategy's non-STREAM
        // mode and stays wave-eligible.
        query.append(
            if (defaultIsStream) {
                " and (releaseModeOverride is not null or (orderStrategyId is not null and orderStrategyId not in :streamIds))"
            } else {
                " and (releaseModeOverride is not null or orderStrategyId is null or orderStrategyId not in :streamIds)"
            },
        )
        params.and("streamIds", streamIds.toList())
    }

    /** Absent keys fall back to [WaveStrategyConfig]'s own defaults. */
    private fun parseConfig(extensionProperties: String): WaveStrategyConfig {
        val node = objectMapper.readTree(extensionProperties)
        val defaults = WaveStrategyConfig()
        return WaveStrategyConfig(
            waveAutoRelease = node.path("waveAutoRelease").asBoolean(defaults.waveAutoRelease),
            waveMaxOrders = node.path("waveMaxOrders").asInt(defaults.waveMaxOrders),
            wavePickMode = node.path("wavePickMode").asText(defaults.wavePickMode),
            waveShortageAction = node.path("waveShortageAction").asText(defaults.waveShortageAction),
            waveSelectionStrategy = node.path("waveSelectionStrategy").asText(defaults.waveSelectionStrategy),
            waveDueWithinDays = node.path("waveDueWithinDays").let { if (it.isMissingNode || it.isNull) defaults.waveDueWithinDays else it.asInt() },
            waveMinPrio = node.path("waveMinPrio").let { if (it.isMissingNode || it.isNull) defaults.waveMinPrio else it.asInt() },
            waveIncludeUndated = node.path("waveIncludeUndated").asBoolean(defaults.waveIncludeUndated),
            waveSelectionRuleId = node.path("waveSelectionRuleId").let { if (it.isMissingNode || it.isNull) defaults.waveSelectionRuleId else it.asLong() },
        )
    }

    private fun toWaveOrderView(order: DeliveryOrder): WaveOrderView = WaveOrderView(
        orderId = order.id!!,
        orderNumber = order.orderNumber,
        state = order.state,
        prio = order.prio,
        created = order.created,
        customerName = order.customerName,
        zipCode = order.zipCode,
        city = order.city,
        lineCount = order.lines.size,
        deliveryDate = order.deliveryDate,
        country = order.country,
        externalNumber = order.externalNumber,
        street = order.street,
        streetNumber = order.streetNumber,
    )
}

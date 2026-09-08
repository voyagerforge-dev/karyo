package com.karyo.orders.spi
import com.karyo.orders.vo.ReleaseMode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Wave-side orchestration seam. Every method takes explicit clientId (scheduler doctrine). */
interface OrderReleasePort {
    /** CREATED, un-waved orders eligible for waving, oldest first. strategyId null = any strategy. */
    fun findWaveEligible(strategyId: Long?, clientId: Long, limit: Int): List<WaveOrderView>
    /** Stamp wave membership. Refuses (IllegalStateException) if any order is not CREATED or already waved. */
    fun assignToWave(orderIds: List<Long>, waveId: Long, clientId: Long)
    /** Clear membership (wave cancel, HOLD_ORDER). Idempotent. */
    fun clearWave(orderIds: List<Long>, clientId: Long)
    /** Members of a wave with live state. */
    fun memberViews(waveId: Long, clientId: Long): List<WaveOrderView>
    /** waveId of the order, or null. */
    fun waveIdOf(orderId: Long, clientId: Long): Long?

    /**
     * Every order line of a wave's member orders, as `deliveryOrderLineId -> deliveryOrderId`, in
     * ONE query. The sort station's put-wall picture needs the owning order of each picked line
     * and used to walk `DeliveryOrderLookup.findForPicking` once per member (an N+1 on every scan,
     * undo, status and wave detail). Tenant-scoped through the owning order, like every other
     * method here.
     */
    fun memberLineOwners(waveId: Long, clientId: Long): Map<Long, Long>
    /** Existing release path (validators + reserve per line). Returns per-line shortage snapshot. */
    fun releaseForWave(orderId: Long, clientId: Long): WaveReleaseOutcome
    /** Release the recorded reservations of these lines (netting against terminal picks, floored
     *  at zero), zero the lines' reservedAmount, leave line/order states alone (lines may sit
     *  PENDING; retry-reservation remains available). Used by SKIP and HOLD_ORDER. */
    fun releaseLineReservations(lineIds: List<Long>, clientId: Long)
    /** Strategies whose extensionProperties opt into wave auto-release. */
    fun waveEnabledStrategies(): List<WaveStrategyView>
    /** Parsed wave config for a strategy (defaults when keys absent). Null strategyId = defaults. */
    fun waveConfig(strategyId: Long?): WaveStrategyConfig
    /** Every line id of [orderId] (any state), tenant-scoped. Task 6's wave release HOLD_ORDER path
     *  needs ALL of a dropped order's lines released, not just the line whose shortage triggered it. */
    fun lineIdsOf(orderId: Long, clientId: Long): List<Long>
    /** Live shortage snapshot (reservedAmount < amount) across a wave's CURRENT member orders --
     *  recomputed on every read for `WaveDetailResponse.shortages` (Task 6); nothing about a
     *  shortage is persisted wave-side. */
    fun shortagesOf(waveId: Long, clientId: Long): List<ShortageView>

    /**
     * Distinct client ids with at least one CREATED, un-waved order -- the tenant loop
     * `WaveScheduler.runOnce` (Task 9) sweeps. Deliberately unscoped, same native-query "distinct
     * client ids from the domain data this scheduler cares about" shape as
     * `FixAssignmentLookup.clientIdsWithAssignments` / `StockUnitRepository
     * .clientIdsWithDeletableStock` / `TenantEnumerator.activeClientIds`.
     */
    fun clientIdsWithWaveEligibleOrders(): List<Long>

    /** Order-strategy ids whose extensionProperties bind the given selection rule id. */
    fun strategiesBindingRule(ruleId: Long): List<Long>
}

data class WaveOrderView(
    val orderId: Long, val orderNumber: String, val state: Int, val prio: Int,
    val created: Instant, val customerName: String?, val zipCode: String?, val city: String?,
    val lineCount: Int,
    val deliveryDate: LocalDate? = null,
    val country: String? = null,
    val externalNumber: String? = null,
    /** Sort-station sprint (Sprint A): ship-to street parts so the consolidation key is the full
     *  address. Nullable + defaulted so every existing constructor call keeps compiling. */
    val street: String? = null,
    val streetNumber: String? = null,
)
data class WaveReleaseOutcome(val orderId: Long, val shortages: List<ShortageView>)
data class ShortageView(
    val orderId: Long, val lineId: Long, val itemDataId: Long, val itemDataNumber: String,
    val requested: BigDecimal, val reserved: BigDecimal,
)
data class WaveStrategyView(val strategyId: Long, val name: String, val config: WaveStrategyConfig)
data class WaveStrategyConfig(
    val waveAutoRelease: Boolean = false,
    val waveMaxOrders: Int = 200,
    val wavePickMode: String = "HYBRID",
    val waveShortageAction: String = "SKIP",
    val waveSelectionStrategy: String = "due-date-priority",
    val waveDueWithinDays: Int? = null,
    val waveMinPrio: Int? = null,
    val waveIncludeUndated: Boolean = true,
    val waveSelectionRuleId: Long? = null,
)

/**
 * Streaming knobs parsed from OrderStrategy.extensionProperties (order streaming, B3). Absent or
 * unparsable keys fall back to these defaults; streamBatchSize is clamped to 1..1000 and
 * streamAbandonSeconds is floored at streamMaxWaitSeconds (spec Configuration table).
 */
data class StreamingStrategyConfig(
    val releaseMode: ReleaseMode = ReleaseMode.MANUAL,
    val streamBatchSize: Int = 50,
    val streamMaxWaitSeconds: Int = 30,
    val streamAbandonSeconds: Int = 1800,
    val streamTimingStrategy: String = "time-size",
)

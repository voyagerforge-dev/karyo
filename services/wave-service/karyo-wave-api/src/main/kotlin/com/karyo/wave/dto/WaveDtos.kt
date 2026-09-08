package com.karyo.wave.dto

import com.karyo.wave.rule.SelectionRule
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class CreateWaveRequest(
    val orderStrategyId: Long,
    val deliveryOrderIds: List<Long>?,
    val plannedReleaseAt: Instant?,
    val wavePickMode: String?,
    val shortageAction: String?,
    // Selection-rules sprint, Task 4: per-request override, wins over the strategy's own config.
    val selectionStrategy: String? = null,
    val selectionRuleId: Long? = null,
)

data class WaveResponse(
    val id: Long,
    val waveNumber: String,
    val state: String,
    val orderStrategyId: Long,
    val wavePickMode: String,
    val shortageAction: String,
    val plannedReleaseAt: Instant?,
    val releasedAt: Instant?,
    val completedAt: Instant?,
    val totalOrders: Int,
    val totalLines: Int,
    val created: Instant,
    // Selection-rules sprint, Task 4: selection provenance -- "explicit" for an explicit-ids
    // create, the resolved key otherwise; selectionRuleName is resolved live, "#<id>" if the
    // bound rule was since deleted.
    val selectionStrategy: String?,
    val selectionRuleName: String?,
)

data class WaveDetailResponse(
    val wave: WaveResponse,
    val orders: List<WaveOrderSummary>,
    val shortages: List<WaveShortage>,
    val groups: List<ConsolidationGroupResponse>,
    /**
     * Sprint C: member orders whose wave picks are all terminal yet delivered NOTHING (every
     * slice short-confirmed to zero by the bulk fan-out). They are excluded from their group's
     * shipment -- there is no content to pack -- so this list is the only place they are
     * accounted for. Additive default so every existing construction still compiles.
     */
    val unfilledOrders: List<WaveOrderSummary> = emptyList(),
)

data class WaveOrderSummary(
    val orderId: Long,
    val orderNumber: String,
    val state: String,
    val prio: Int,
    val customerName: String?,
    // FOLD-6 (review): the spec lists deliveryDate as part of the preview/detail sample --
    // additive default so any existing construction that doesn't pass it still compiles.
    val deliveryDate: LocalDate? = null,
)

data class WaveShortage(
    val orderId: Long,
    val lineId: Long,
    val itemDataNumber: String,
    val requested: BigDecimal,
    val reserved: BigDecimal,
    val action: String
)

data class ConsolidationGroupResponse(
    val id: Long,
    val destinationKey: String,
    val state: String,
    val consolidationLocationId: Long?,
    val totalPickOrders: Int,
    val completedPickOrders: Int,
    /** Sprint A: put-wall slot label and live sort counters (derived, never stored). */
    val sortSlot: String,
    val pickedAmount: BigDecimal,
    val sortedAmount: BigDecimal,
    /** Sprint C: how much of [sortedAmount] has already gone into a container of the group's
     *  live shipment (derived from `ConsolidationPackPort.packedByLines` -- live GROUP shipments
     *  only, the put wall's universe; burndown-6 A6 -- never stored). */
    val packedAmount: BigDecimal = BigDecimal.ZERO,
    /** Sprint C: the group's one live (non-canceled) group shipment, or null when pack-out has
     *  not been opened yet. */
    val shipmentId: Long? = null,
)

data class WaveProgressResponse(
    val waveId: Long,
    val state: String,
    val totalOrders: Int,
    val totalLines: Int,
    val pickedLines: Int,
    val openPickOrders: Int,
    val groupsReady: Int,
    val groupsTotal: Int
)

// ── Selection-rules sprint, Task 3 ──────────────────────────────────────────

data class SelectionRuleRequest(val name: String, val description: String?, val definition: SelectionRule)

data class SelectionRuleResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val definition: SelectionRule,
    val boundByStrategies: Int,
    val created: Instant,
)

data class SelectionFieldResponse(val field: String, val type: String, val label: String, val ops: List<String>)

data class RulePreviewResponse(val matchedCount: Int, val poolSize: Int, val sample: List<WaveOrderSummary>)

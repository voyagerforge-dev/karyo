package com.karyo.wave.spi

import com.karyo.orders.spi.WaveOrderView
import com.karyo.wave.rule.SelectionRule

/**
 * Pluggable wave-candidate selection (selection-rules sprint, Task 1). Registered by [key];
 * `WaveStrategyConfig.waveSelectionStrategy` names the active one per order strategy. Mirrors the
 * existing [AllocationCustomizer]/[ConsolidationCustomizer] "registry key + one method" SPI shape.
 */
interface WaveSelectionStrategy {
    /** Registry key. Unknown keys: 422 on create, warn+skip in the scheduler. */
    val key: String

    /** Pick and order the orders that form the wave, at most ctx.maxOrders. */
    fun select(candidates: List<WaveOrderView>, ctx: WaveSelectionContext): List<WaveOrderView>
}

data class WaveSelectionContext(
    val orderStrategyId: Long?,
    val clientId: Long,
    val maxOrders: Int,
    val dueWithinDays: Int?,
    val minPrio: Int?,
    val includeUndated: Boolean,
    val rule: SelectionRule?,
)

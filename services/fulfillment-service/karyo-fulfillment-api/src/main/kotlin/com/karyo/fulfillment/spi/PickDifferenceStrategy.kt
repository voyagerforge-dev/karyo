package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Strategy-SPI (ADR-036): decides what happens to a short pick's SOURCE residual — the quantity the
 * bin was short of. Priority-ordered, first-non-null-wins, built-in (Leave) runs last. v1.3 ships
 * only Leave (leave the stock on the bin, exclude it from the shortfall re-selection so the follow-up
 * finds other stock); future WRITE_OFF (decrement the source as a stock difference) and QUARANTINE
 * (lock the residual for recount) register as beans and win by priority / the pickDifferenceStrategy knob.
 */
interface PickDifferenceStrategy {
    /** Lower runs first; built-in Leave uses a high value so any custom strategy wins. */
    val priority: Int

    /** The OrderStrategy.pickDifferenceStrategy name this strategy answers to (built-in: "LEAVE"). */
    val name: String

    /** Handle the source residual; return the resolution (incl. which units to exclude), or null to defer. */
    fun handle(context: PickDifferenceContext): PickDifferenceResolution?
}

/** Inputs describing the short pick's source residual. */
data class PickDifferenceContext(
    val sourceStockUnitId: Long,
    val itemDataId: Long,
    val shortfall: BigDecimal,
    val clientId: Long,
    val pickDifferenceStrategyName: String,
)

/** Outcome. [excludeStockUnitIds] = stock units the follow-up re-selection must skip (the short source for LEAVE). */
data class PickDifferenceResolution(
    val excludeStockUnitIds: List<Long>,
)

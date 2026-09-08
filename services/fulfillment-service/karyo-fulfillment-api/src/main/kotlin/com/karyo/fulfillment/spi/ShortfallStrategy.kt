package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Strategy-SPI (ADR-036): decides what happens to a short pick's *uncovered* remainder (after the
 * shortPickMode cover-attempt). Priority-ordered, first-non-null-wins, built-in (PartialShip) runs
 * last. v1.3 ships only PartialShip (accept + report); PENDING-escalation and auto-recovery are
 * future strategies that register as beans and win by priority / the shortfallStrategy knob.
 */
interface ShortfallStrategy {
    /** Lower runs first; built-in PartialShip uses a high value so any custom strategy wins. */
    val priority: Int

    /** The OrderStrategy.shortfallStrategy name this strategy answers to (built-in: "PARTIAL_SHIP"). */
    val name: String

    /** Resolve the [context]'s remainder, or null to defer to the next strategy. */
    fun handle(context: ShortfallContext): ShortfallResolution?
}

/** Inputs describing the uncovered remainder of a short pick. */
data class ShortfallContext(
    val pickId: Long,
    val deliveryOrderId: Long,
    val deliveryOrderLineId: Long,
    val itemDataId: Long,
    val remainder: BigDecimal,
    val clientId: Long,
    val shortfallStrategyName: String,
)

/** Outcome. [shortShipped] = remainder accepted as not-fulfilled (the built-in). */
data class ShortfallResolution(
    val shortShipped: BigDecimal,
)

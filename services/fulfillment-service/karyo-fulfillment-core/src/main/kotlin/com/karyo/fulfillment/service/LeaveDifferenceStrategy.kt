package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.PickDifferenceContext
import com.karyo.fulfillment.spi.PickDifferenceResolution
import com.karyo.fulfillment.spi.PickDifferenceStrategy
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in source handler: LEAVE. The short source's residual stays on the bin untouched; it is only
 * excluded from the shortfall re-selection so the follow-up sources from elsewhere (the phantom is
 * reconciled later by cycle-count). Lowest priority (runs last); answers the "LEAVE" name. Future
 * WRITE_OFF/QUARANTINE strategies pre-empt by priority + the pickDifferenceStrategy knob.
 */
@ApplicationScoped
class LeaveDifferenceStrategy : PickDifferenceStrategy {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = "LEAVE"

    override fun handle(context: PickDifferenceContext): PickDifferenceResolution =
        PickDifferenceResolution(excludeStockUnitIds = listOf(context.sourceStockUnitId))
}

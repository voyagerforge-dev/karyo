package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.GroupingRequest
import com.karyo.fulfillment.spi.GroupingResult
import com.karyo.fulfillment.spi.PickOrderGroupingStrategy
import com.karyo.fulfillment.spi.PlannedPickGroup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in grouping: one DeliveryOrder -> one PickOrder (discrete single-order picking). Runs at
 * the lowest priority (highest number) so any custom batch/wave strategy wins. Never returns null.
 */
@ApplicationScoped
class DiscreteGroupingStrategy : PickOrderGroupingStrategy {
    override val priority: Int = Int.MAX_VALUE

    override fun group(request: GroupingRequest): GroupingResult =
        GroupingResult(groups = listOf(PlannedPickGroup(picks = request.plannedPicks)))
}

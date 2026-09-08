package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Strategy-SPI: given a release request, decides how an order's reserved work is grouped
 * and sequenced into PickOrders. Priority-ordered, first-non-null-wins, built-in (Discrete) runs
 * last. v1.3 ships only DiscreteGroupingStrategy (one order -> one PickOrder); batch/cluster/zone/
 * wave grouping can compose here without changing the PickOrder contract.
 */
interface PickOrderGroupingStrategy {
    /** Lower runs first; built-in Discrete uses a high value so any custom strategy wins. */
    val priority: Int

    /**
     * Returns the grouping for this request, or null to defer to the next strategy. The built-in
     * never returns null. A returned group's picks reference the order's reservation slices 1:1.
     */
    fun group(request: GroupingRequest): GroupingResult?
}

/** Input: the released order plus the per-slice planned picks already flattened from reservations. */
data class GroupingRequest(
    val deliveryOrderId: Long,
    val deliveryOrderNumber: String,
    val clientId: Long,
    val plannedPicks: List<PlannedPick>,
)

/**
 * One planned pick = one reservation slice. [deliveryOrderLineId] is null for an EXTINGUISH
 * (stock-clearance) planned pick (WORKLIST row 20, V605) — there is no DeliveryOrder line to
 * bind to; see [com.karyo.fulfillment.service.PickTopUpService.addPicksToOrder]'s EXT-marker
 * match rule for how a null value is handled on merge.
 */
data class PlannedPick(
    val deliveryOrderLineId: Long?,
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val sourceStockUnitId: Long,
    val amount: BigDecimal,
)

/** Output: the PickOrders to create, each carrying its slice of planned picks. */
data class GroupingResult(
    val groups: List<PlannedPickGroup>,
)

/** One PickOrder-to-be. v1.3 Discrete yields exactly one group holding all plannedPicks. */
data class PlannedPickGroup(
    val picks: List<PlannedPick>,
)

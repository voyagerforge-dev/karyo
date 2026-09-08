package com.karyo.work.service

import com.karyo.work.dto.WorkItem
import com.karyo.work.spi.WorkDispatchStrategy
import jakarta.enterprise.context.ApplicationScoped

/**
 * Opt-in dispatch ordering that walks the floor instead of strictly chasing priority (St6,
 * stocktaking-block sprint). Orders `travelOrder NULLS LAST, priority DESC, createdAt ASC` — the
 * same priority/createdAt tiebreak [StrictPriorityDispatchStrategy] uses, with
 * [WorkItem.travelOrder] spliced in as the PRIMARY key. An item with no `travelOrder` (every
 * provider except [com.karyo.stocktaking.messaging.CountWorkProvider] today) sorts after every
 * item that has one, then falls back to priority/createdAt among itself — so a mixed pool
 * (e.g. COUNT items with a travel order alongside location-less PICK items) degrades to
 * [StrictPriorityDispatchStrategy]'s behavior for the items this strategy has no spatial opinion
 * about, rather than scattering them arbitrarily.
 *
 * **Not the active strategy by default.** Selected only by setting
 * `karyo.work.dispatch-strategy=TRAVEL_PATH` (env `KARYO_WORK_DISPATCH_STRATEGY`) — see
 * [WorkDispatchStrategy]'s KDoc for the name-driven resolver and why [priority] (100, matching
 * [com.karyo.stocktaking.service.FullWarehouseScope]'s convention for "a second built-in that
 * doesn't want to collide with `Int.MAX_VALUE`") plays no part in that selection.
 *
 * **Provenance: karyo-invented.** Legacy myWMS's next-location suggestion ordered candidates
 * alphabetically by name; there is no myWMS concept of a spatial/travel-path dispatch ordering.
 * This strategy is Karyo-native, built on the pre-existing `WorkDispatchStrategy` seam.
 */
@ApplicationScoped
class TravelPathDispatchStrategy : WorkDispatchStrategy {
    override val priority: Int = TRAVEL_PATH_PRIORITY
    override val name: String = "TRAVEL_PATH"

    override fun order(items: List<WorkItem>): List<WorkItem> =
        items.sortedWith(
            compareBy<WorkItem, Int?>(nullsLast()) { it.travelOrder }
                .thenByDescending { it.priority }
                .thenBy { it.createdAt },
        )

    companion object {
        private const val TRAVEL_PATH_PRIORITY = 100
    }
}

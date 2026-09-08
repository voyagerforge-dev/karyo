package com.karyo.work.service

import com.karyo.work.dto.WorkItem
import com.karyo.work.spi.WorkDispatchStrategy
import jakarta.enterprise.context.ApplicationScoped

/**
 * Higher priority value first; oldest createdAt first as tiebreak (FIFO). The built-in, and the
 * default active strategy — `name = "STRICT_PRIORITY"` is [WorkDispatchService]'s
 * `karyo.work.dispatch-strategy` default, not [priority]; see [WorkDispatchStrategy]'s KDoc for
 * why selection moved off priority in St6.
 */
@ApplicationScoped
class StrictPriorityDispatchStrategy : WorkDispatchStrategy {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = "STRICT_PRIORITY"

    override fun order(items: List<WorkItem>): List<WorkItem> =
        items.sortedWith(compareByDescending<WorkItem> { it.priority }.thenBy { it.createdAt })
}

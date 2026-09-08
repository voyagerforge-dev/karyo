package com.karyo.work.spi

import com.karyo.work.vo.WorkType

/**
 * Resolves the set of work-types an operator is eligible for. Lower [priority] wins when
 * multiple are registered (built-in uses Int.MAX_VALUE so any custom resolver takes precedence).
 */
interface WorkEligibilityResolver {
    val priority: Int
    val name: String
    fun resolve(operatorId: String, clientId: Long): Set<WorkType>
}

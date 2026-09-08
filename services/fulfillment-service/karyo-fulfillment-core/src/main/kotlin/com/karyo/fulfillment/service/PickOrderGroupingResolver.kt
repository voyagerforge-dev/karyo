package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.GroupingRequest
import com.karyo.fulfillment.spi.GroupingResult
import com.karyo.fulfillment.spi.PickOrderGroupingStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Resolves the active [PickOrderGroupingStrategy]: walks all beans in ascending priority and uses
 * the first non-null group() result (built-in Discrete, priority MAX_VALUE, runs last and always
 * answers). v1.3 has only the built-in; custom strategies register as CDI beans and win by priority.
 * Mirrors the ADR-036 resolver idiom established by OrderStrategyService.resolve().
 */
@ApplicationScoped
class PickOrderGroupingResolver(
    private val strategies: Instance<PickOrderGroupingStrategy>,
) {
    fun resolve(request: GroupingRequest): GroupingResult =
        strategies.sortedBy { it.priority }
            .firstNotNullOfOrNull { it.group(request) }
            ?: error("No PickOrderGroupingStrategy produced a grouping (built-in Discrete missing?)")
}

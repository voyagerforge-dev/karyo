package com.karyo.replenishment.service

import com.karyo.replenishment.spi.ReplenishmentStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

@ApplicationScoped
class ReplenishmentStrategyResolver(
    private val strategies: Instance<ReplenishmentStrategy>,
) {
    fun resolve(name: String? = null): ReplenishmentStrategy {
        val ordered = strategies.sortedBy { it.priority }
        return (name?.let { n -> ordered.firstOrNull { it.name == n } } ?: ordered.firstOrNull())
            ?: error("No ReplenishmentStrategy registered (built-in MIN_MAX missing?)")
    }
}

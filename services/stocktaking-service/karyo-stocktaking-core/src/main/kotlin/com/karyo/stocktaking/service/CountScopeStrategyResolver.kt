package com.karyo.stocktaking.service

import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.spi.CountScopeStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Resolves which [CountScopeStrategy] to use.
 *
 * When [name] is supplied the registered strategy answering to that name is returned, and an
 * unknown name is a hard [StocktakingException.InvalidCount] (422). It used to fall back to the
 * highest-priority strategy — harmless while [ExplicitLocationScope] was the only one
 * registered, but actively dangerous since St5 added [FullWarehouseScope] at priority 100: a
 * typo in a caller-supplied `scopeStrategy` would have silently escalated a targeted cycle count
 * into a warehouse-wide freeze. Fail loudly instead.
 *
 * When [name] is omitted the highest-priority (lowest [CountScopeStrategy.priority]) strategy
 * wins — which is now [FullWarehouseScope], NOT the explicit scope. [StocktakingService]
 * therefore never calls this unnamed; see [FullWarehouseScope]'s priority-trap note.
 */
@ApplicationScoped
class CountScopeStrategyResolver(
    private val strategies: Instance<CountScopeStrategy>,
) {
    fun resolve(name: String? = null): CountScopeStrategy {
        val ordered = strategies.sortedBy { it.priority }
        if (name != null) {
            return ordered.firstOrNull { it.name == name }
                ?: throw StocktakingException.InvalidCount("unknown count scope strategy '$name'")
        }
        return ordered.firstOrNull()
            ?: error("No CountScopeStrategy registered (built-in EXPLICIT missing?)")
    }
}

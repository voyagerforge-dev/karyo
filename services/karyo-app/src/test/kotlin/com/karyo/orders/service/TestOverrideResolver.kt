package com.karyo.orders.service

import com.karyo.orders.spi.OrderStrategyContext
import com.karyo.orders.spi.OrderStrategyResolver
import jakarta.enterprise.context.ApplicationScoped

/**
 * Test-only resolver. Inert (returns null) unless a test sets [overrideName], so it never
 * affects unrelated tests. priority() = 1 sorts it ahead of the built-in (MAX_VALUE).
 */
@ApplicationScoped
class TestOverrideResolver : OrderStrategyResolver {
    override fun resolve(context: OrderStrategyContext): String? = overrideName
    override fun priority(): Int = 1

    companion object {
        @JvmStatic
        var overrideName: String? = null
    }
}

package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.ShortfallContext
import com.karyo.fulfillment.spi.ShortfallResolution
import com.karyo.fulfillment.spi.ShortfallStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Resolves the active [ShortfallStrategy]. Prefers a strategy whose [ShortfallStrategy.name] matches
 * the order's `shortfallStrategy` knob; otherwise walks ascending priority and takes the first
 * non-null result (built-in PartialShip, priority MAX_VALUE, always answers). v1.3 has only the
 * built-in; future strategies register as beans + claim a name.
 */
@ApplicationScoped
class ShortfallStrategyResolver(
    private val strategies: Instance<ShortfallStrategy>,
) {
    fun resolve(context: ShortfallContext): ShortfallResolution {
        val ordered = strategies.sortedBy { it.priority }
        val named = ordered.firstOrNull { it.name == context.shortfallStrategyName }
        return named?.handle(context)
            ?: ordered.firstNotNullOfOrNull { it.handle(context) }
            ?: error("No ShortfallStrategy handled the remainder (built-in PartialShip missing?)")
    }
}

package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.PickDifferenceContext
import com.karyo.fulfillment.spi.PickDifferenceResolution
import com.karyo.fulfillment.spi.PickDifferenceStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Resolves the active [PickDifferenceStrategy]. Prefers a strategy whose [PickDifferenceStrategy.name]
 * matches the order's `pickDifferenceStrategy` knob; otherwise walks ascending priority and takes the
 * first non-null result (built-in Leave, priority MAX_VALUE, always answers). v1.3 has only the built-in.
 */
@ApplicationScoped
class PickDifferenceStrategyResolver(
    private val strategies: Instance<PickDifferenceStrategy>,
) {
    fun resolve(context: PickDifferenceContext): PickDifferenceResolution {
        val ordered = strategies.sortedBy { it.priority }
        val named = ordered.firstOrNull { it.name == context.pickDifferenceStrategyName }
        return named?.handle(context)
            ?: ordered.firstNotNullOfOrNull { it.handle(context) }
            ?: error("No PickDifferenceStrategy handled the source (built-in Leave missing?)")
    }
}

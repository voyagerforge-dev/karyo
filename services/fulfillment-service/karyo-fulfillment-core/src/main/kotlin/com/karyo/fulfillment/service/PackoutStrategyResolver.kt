package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.PackoutContext
import com.karyo.fulfillment.spi.PackoutResult
import com.karyo.fulfillment.spi.PackoutStrategy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Resolves the active [PackoutStrategy]. Prefers a strategy whose [PackoutStrategy.name] matches the
 * order's `packoutStrategy` knob; otherwise walks ascending priority and takes the first non-null
 * result (built-in OneToOne, priority MAX_VALUE, always answers). Additional strategies register
 * as beans and claim a name.
 *
 * **Contract for a null-returning named strategy (outbound-completion sprint, Task 3):** a strategy
 * whose [PackoutStrategy.name] matches the knob but whose `pack()` returns null (e.g. a
 * license-gated strategy that isn't entitled, "defer cheaply") falls through to the ordered
 * priority scan same as an unnamed miss -- but that scan must SKIP the already-tried named
 * instance. Without the skip, the fallback re-invokes the same instance a second time, which is
 * wasted work at best and a correctness hazard for any strategy whose `pack()` isn't a pure
 * decision (e.g. one that consumes a sequence or logs a metric per call).
 */
@ApplicationScoped
class PackoutStrategyResolver(
    private val strategies: Instance<PackoutStrategy>,
) {
    fun resolve(context: PackoutContext): PackoutResult {
        val ordered = strategies.sortedBy { it.priority }
        val named = ordered.firstOrNull { it.name == context.packoutStrategyName }
        return named?.pack(context)
            ?: ordered.filter { it !== named }.firstNotNullOfOrNull { it.pack(context) }
            ?: error("No PackoutStrategy produced a result (built-in OneToOne missing?)")
    }
}

package com.karyo.orders.service

import com.karyo.orders.domain.model.OrderStrategy
import com.karyo.orders.repository.OrderStrategyRepository
import com.karyo.orders.spi.OrderStrategyContext
import com.karyo.orders.spi.OrderStrategyResolver
import jakarta.enterprise.context.ApplicationScoped

/**
 * ADR-036 built-in order-strategy resolver: returns the directly-referenced strategy's name,
 * or the seeded DEFAULT when the order has no explicit reference. Lowest priority, so any
 * custom resolver pre-empts it.
 */
@ApplicationScoped
class DefaultOrderStrategyResolver(
    private val repository: OrderStrategyRepository,
) : OrderStrategyResolver {

    override fun resolve(context: OrderStrategyContext): String {
        val id = context.orderStrategyId ?: return OrderStrategy.DEFAULT_NAME
        // The id is validated at write time (create/update call resolveEntity), so a miss here
        // means a since-deleted strategy: deliberately fall back to DEFAULT rather than fail a pick.
        return repository.findById(id)?.name ?: OrderStrategy.DEFAULT_NAME
    }

    override fun priority(): Int = OrderStrategyResolver.DEFAULT_PRIORITY
}

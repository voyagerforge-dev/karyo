package com.karyo.orders.spi

/**
 * ADR-036 Strategy-Driven Configuration — the binding seam for the order domain.
 *
 * Resolves *which* [com.karyo.orders.domain.model.OrderStrategy] (by name) applies to an
 * operation, given a [OrderStrategyContext]. Discovered as CDI beans and consulted in
 * ascending [priority] order; the **first non-null** name wins and short-circuits the rest.
 * The built-in [com.karyo.orders.service.DefaultOrderStrategyResolver] registers at the
 * **lowest priority** ([DEFAULT_PRIORITY], runs last), so any custom resolver at a lower
 * value pre-empts it. Returning `null` means "no opinion, fall through to the next resolver".
 *
 * Today the context carries only the direct `orderStrategyId` reference; future inputs
 * (orderType, wave) are added to [OrderStrategyContext] without breaking existing resolvers.
 */
interface OrderStrategyResolver {

    /** The OrderStrategy name to apply for [context], or null to defer to the next resolver. */
    fun resolve(context: OrderStrategyContext): String?

    /** Lower runs first; the built-in resolver uses [DEFAULT_PRIORITY] (runs last). */
    fun priority(): Int = DEFAULT_PRIORITY

    companion object {
        /** Lowest priority — the built-in resolver; custom resolvers use lower values to pre-empt. */
        const val DEFAULT_PRIORITY = Int.MAX_VALUE
    }
}

/**
 * Inputs available for resolving an order's strategy. Extend with new optional fields
 * (orderType, prio, waveId) as binding evolves — existing resolvers keep compiling.
 */
data class OrderStrategyContext(
    /** The order's direct strategy reference (`DeliveryOrder.orderStrategyId`); null = none set. */
    val orderStrategyId: Long?,
    /** Goods-owner client of the order. */
    val clientId: Long,
)

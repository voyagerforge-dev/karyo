package com.karyo.orders.spi

/**
 * In-process read seam: resolves the picking-relevant knobs for an order (via the configured
 * strategy resolver chain), so the fulfillment module can drive short-pick recovery with the
 * same settings the order was released under. Returns null when the order doesn't exist /
 * belongs to another tenant.
 */
interface OrderStrategyLookup {
    fun findPickingStrategy(orderId: Long): PickingStrategyView?

    /**
     * Explicit-`clientId` overload of [findPickingStrategy] -- scoped by strict `clientId`
     * equality (not the ambient-`TenantContext` convention the single-arg overload uses). Task 4
     * review fix (IMPORTANT-2, wave bulk fulfillment sprint): `WavePickService.routeMemberOrder`
     * is reachable from `WaveService.release`'s call graph, which is now also reachable from
     * `WaveScheduler`'s `@Scheduled` multi-tenant auto-release loop -- that thread never primes
     * `TenantContext` (the scheduler doctrine documented on `ReplenishmentScheduler`/
     * `CrossDockExpirySweep`). The single-arg overload would silently resolve against the
     * scheduler thread's unassigned default scope (`clientId = 0`), find no matching order (or
     * the wrong tenant's), and return null every time -- silently degrading every wave
     * COMPLETE PickOrder's `defaultDestinationLocationId` fallback to inert. Same shape as
     * [com.karyo.orders.spi.DeliveryOrderLookup.findForPicking]'s explicit-`clientId` overload
     * for the identical reason.
     */
    fun findPickingStrategy(orderId: Long, clientId: Long): PickingStrategyView?
}

/** The order's resolved picking knobs (mirrors the OrderStrategy fields fulfillment cares about). */
data class PickingStrategyView(
    val shortPickMode: String,
    val shortfallStrategy: String,
    val pickDifferenceStrategy: String,
    val packoutStrategy: String,
    val useLockedStock: Boolean,
    val preferComplete: Boolean,
    val preferMatching: Boolean,
    val completeHandling: Int,
    val enforceLot: Boolean,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.sendToPacking]. */
    val sendToPacking: Boolean,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.sendToShipping]. */
    val sendToShipping: Boolean,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.createShippingOrder]. */
    val createShippingOrder: Boolean,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.createTypeOrders]. */
    val createTypeOrders: Boolean,
    /** Row 8. See [com.karyo.orders.domain.model.OrderStrategy.defaultDestinationLocationId]. */
    val defaultDestinationLocationId: Long?,
)

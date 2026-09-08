package com.karyo.fulfillment.spi

/**
 * In-process release seam for the streaming engine (order streaming, Task 3): pushes one already-
 * reserved DeliveryOrder to picking with an explicit `clientId`, never the ambient `TenantContext`
 * -- the paid streaming scheduler (Task 5) runs on a `@Scheduled` thread that never primes it (the
 * same scheduler doctrine as [com.karyo.orders.spi.OrderReleasePort]).
 */
interface PickReleasePort {
    /**
     * Releases [deliveryOrderId] to picking for [clientId] and returns the created PickOrder ids.
     * Throws [com.karyo.fulfillment.exception.FulfillmentException.NotFound] when the order
     * doesn't exist or belongs to another tenant, and
     * [com.karyo.fulfillment.exception.FulfillmentException.NotReleasable] when it is already
     * released, has no reserved stock to pick, or no PACK_STAGING location is configured.
     */
    fun releaseToPicking(deliveryOrderId: Long, clientId: Long): List<Long>
}

package com.karyo.orders.spi

import java.math.BigDecimal

/**
 * In-process read seam for the fulfillment module: fetch a released order plus the per-line
 * reservation slices (each slice = one Pick source). Mirrors product's ProductLookup. Returns
 * null when the order doesn't exist or belongs to another tenant.
 */
interface DeliveryOrderLookup {
    fun findForPicking(orderId: Long): OrderForPicking?

    /**
     * Explicit-`clientId` overload of [findForPicking] -- scoped by strict `clientId` equality
     * (not the ambient `TenantContext.clientId` the single-arg overload reads). Wave bulk
     * fulfillment sprint Task 9: `WavePickService.routeMemberOrder`'s sole caller is
     * `generateForWave`, reachable from `WaveService.release`'s call graph -- and `release` is
     * now also reachable from `WaveScheduler`'s `@Scheduled` multi-tenant auto-release loop,
     * whose thread never primes `TenantContext`. The single-arg overload would silently resolve
     * to the scheduler thread's unassigned default (`clientId = 0`), find no matching order, and
     * throw `FulfillmentException.ValidationFailed("... not found for client ...")` for every
     * single wave member on a scheduler-driven release. Same shape as [orderIdForLine]'s
     * pre-existing explicit-`clientId` overload for the identical reason.
     */
    fun findForPicking(orderId: Long, clientId: Long): OrderForPicking?

    /** The order's ship-to (customer + delivery address + contact) for document generation. Null if not found. */
    fun findShipTo(orderId: Long): ShipToView?

    /**
     * True when the order is CANCELED(900). Deliberately narrower than a full state accessor: the
     * only cross-module question fulfillment needs to ask about a DeliveryOrder's own state is
     * "may I still start downstream work for it", and exposing the whole 18-state machine across
     * the seam would invite fulfillment-side state reasoning that belongs in orders.
     *
     * Returns **false** for an order that doesn't exist or belongs to another tenant — "not
     * canceled as far as this tenant can see". Callers reach this only after already resolving
     * tenant-scoped work bound to the order, so a not-found order is not a guard bypass.
     */
    fun isCanceled(orderId: Long): Boolean

    /**
     * Task 4 review fix (CRITICAL-1, wave bulk fulfillment): resolves a DeliveryOrderLine's
     * owning order id, tenant-scoped by the explicit [clientId] parameter (not the ambient
     * TenantContext -- this seam is reached from a wave-linked pick confirm, which already
     * carries an explicit clientId end to end through BatchPickPort/WavePickRequest). Used to
     * determine which member order a cross-order BATCH PickOrder's just-confirmed Pick belongs
     * to, since a batch PickOrder itself carries no `deliveryOrderId`. Null when the line
     * doesn't exist or belongs to another tenant.
     */
    fun orderIdForLine(lineId: Long, clientId: Long): Long?
}

data class OrderForPicking(
    val orderId: Long,
    val orderNumber: String,
    val state: Int,
    val clientId: Long,
    val lines: List<LineForPicking>,
    /**
     * Row 8: the order's own destination (Task 7, `DeliveryOrder.destinationLocationId`), read
     * here so `PickOrderService.releaseToPicking` can resolve
     * `order.destinationLocationId ?: strategy.defaultDestinationLocationId` without a second
     * cross-module read seam.
     */
    val destinationLocationId: Long? = null,
)

data class LineForPicking(
    val lineId: Long,
    val lineNumber: Int,
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val reservations: List<ReservationSlice>,
)

data class ReservationSlice(
    val stockUnitId: Long,
    val amount: BigDecimal,
)

data class ShipToView(
    val customerName: String?,
    val street: String?,
    val streetNumber: String?,
    val zipCode: String?,
    val city: String?,
    val country: String?,
    val phone: String?,
    val email: String?,
)

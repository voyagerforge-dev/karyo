package com.karyo.orders.spi

/**
 * In-process write seam: lets the fulfillment module advance a DeliveryOrder through the
 * outbound lifecycle without importing orders-core. Each call routes through the order's
 * forward-only transition chokepoint (guard + CDI event + outbox). markPacked/markShipped/
 * markFinished are added with packing/shipping (3.3/3.4).
 *
 * **Tenant sourcing (intentional asymmetry vs [DeliveryOrderLookup]):** the write seam takes
 * [clientId] as an explicit parameter — the caller (a PickOrder owning a known order) states the
 * order-owner tenant — rather than reading the ambient [com.karyo.security.TenantContext] like the
 * read seam does. This fails closed: the impl resolves the order via the tenant-scoped
 * `findEntityById`, so a clientId that doesn't own the order yields NotFound, never a cross-tenant
 * write. Keep these in sync if the sourcing convention is ever unified.
 */
interface OrderProgressionPort {
    /** Advance the order to PICKED(600). Throws on an illegal transition or unknown/foreign order. */
    fun markPicked(orderId: Long, clientId: Long)

    /** Advance the order to STARTED(500) (pick work has begun). Throws on an illegal transition. */
    fun markStarted(orderId: Long, clientId: Long)

    /**
     * Row 8: advance the order to PACKING(640) -- the `sendToPacking` strategy flag's state
     * park, entered between PICKED(600) and PACKED(650). No-op once the order is already at or
     * past PACKING (same idempotency shape as [markPacked]).
     */
    fun markPacking(orderId: Long, clientId: Long)

    /** Advance the order to PACKED(650). Throws on an illegal transition or unknown/foreign order. */
    fun markPacked(orderId: Long, clientId: Long)

    /**
     * Row 8: advance the order to SHIPPING(670) -- the `sendToShipping` strategy flag's state
     * park, entered between PACKED(650) and SHIPPED(680). No-op once the order is already at or
     * past SHIPPING (same idempotency shape as [markShipped]).
     */
    fun markShipping(orderId: Long, clientId: Long)

    /** Advance the order to SHIPPED(680). Throws on an illegal transition or unknown/foreign order. */
    fun markShipped(orderId: Long, clientId: Long)

    /** Advance the order to FINISHED(700) (terminal). Throws on an illegal transition or unknown/foreign order. */
    fun markFinished(orderId: Long, clientId: Long)
}

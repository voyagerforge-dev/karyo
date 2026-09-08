package com.karyo.crossdock.spi

import java.time.Instant

/**
 * Extension seam for what happens when a [com.karyo.crossdock.domain.model.CrossDockOrder]'s
 * staging window expires (the sweep found `stagingDeadline < now` AND the reservation slice
 * still exists -- a gone slice means COMPLETED instead, handled by the sweep itself before this
 * seam is even consulted). The built-in implementation is `DefaultCrossDockExpiryHandler` in
 * crossdock-core (`AUTO_PUTAWAY` releases the slice and mints a fallback putaway; `ALERT`
 * releases nothing and pushes the deadline forward instead) -- see its own KDoc for the
 * per-action rationale. A client extension jar may register a different handler (e.g. paging an
 * operator, a custom disposition workflow) without touching `CrossDockLifecycleService.sweep`
 * (also crossdock-core; not linkable from this module).
 */
interface CrossDockExpiryHandler {
    fun handleExpiry(context: CrossDockExpiryContext)
}

data class CrossDockExpiryContext(
    val crossDockOrderId: Long,
    val orderNumber: String,
    val unitLoadId: Long?,
    val stockUnitId: Long?,
    val deliveryOrderLineId: Long,
    val stagingDeadline: Instant?,
    val clientId: Long,
    val configuredAction: String,
    /**
     * Final fix wave: the still-open CROSS_DOCK transport order this expiring row minted at
     * match time, if any. AUTO_PUTAWAY cancels it (when still open) BEFORE minting its own
     * fallback PUTAWAY transport, so the unit load never carries two competing floor
     * instructions -- see `DefaultCrossDockExpiryHandler.handleAutoPutaway`'s KDoc.
     */
    val transportOrderId: Long? = null,
)

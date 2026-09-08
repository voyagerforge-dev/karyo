package com.karyo.orders.event

import java.time.Instant

/**
 * Fired on every DeliveryOrder state transition. Serves as both the synchronous
 * CDI event type (in-process consumers) and the outbox payload (dormant external
 * log / future webhook + copilot feed) — same dual role as ItemDataStateChangedEvent
 * in karyo-product-api.
 */
data class DeliveryOrderStateChangedEvent(
    val orderId: Long,
    val orderNumber: String,
    val oldState: Int,
    val newState: Int,
    val clientId: Long,
    val occurredAt: Instant,
)

package com.karyo.tasks.event

import java.time.Instant

/**
 * Fired on every TransportOrder state transition. Dual role: the synchronous CDI event
 * type (in-process consumers) and the outbox payload (dormant external log / future
 * webhook + copilot feed) — the same chokepoint pattern as DeliveryOrder/GoodsReceipt.
 */
data class TransportOrderStateChangedEvent(
    val transportOrderId: Long,
    val orderNumber: String,
    val oldState: Int,
    val newState: Int,
    val clientId: Long,
    val occurredAt: Instant,
)

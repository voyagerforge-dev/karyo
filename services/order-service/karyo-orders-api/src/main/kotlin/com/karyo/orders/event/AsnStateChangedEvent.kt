package com.karyo.orders.event

import java.time.Instant

/**
 * Fired on every ASN state transition. Serves as both the synchronous CDI event
 * type (in-process consumers) and the outbox payload (webhook/copilot feed) —
 * same dual role as [DeliveryOrderStateChangedEvent].
 */
data class AsnStateChangedEvent(
    val asnId: Long,
    val asnNumber: String,
    val oldState: Int,
    val newState: Int,
    val clientId: Long,
    val occurredAt: Instant,
)

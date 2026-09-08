package com.karyo.monitors.spi

import com.karyo.monitors.dto.AlertDto

/**
 * SC20 delivery seam: one bean per out-of-app notification channel for monitor alerts.
 *
 * Built-ins: `email` (Quarkus Mailer) and `slack` (incoming-webhook POST) in monitors-core.
 * An extension JAR can register additional channels (SMS, pager, ticketing) by implementing
 * this interface; a channel is addressed by its [key] from the per-monitor `channels` config.
 *
 * Delivery is asynchronous: the fan-out persists one `alert_deliveries` row per enabled
 * channel inside the alert-opening transaction, and a scheduler performs the actual network
 * I/O later, outside any domain transaction (v1.5 webhook-relay shape).
 */
interface AlertDeliveryChannel {
    val key: String                                        // "email", "slack"
    /** Throwing marks the delivery attempt FAILED (retried); DeliveryRejected marks it DEAD. */
    fun deliver(alert: AlertDto, clientId: Long)
}

class DeliveryRejected(message: String) : RuntimeException(message)

package com.karyo.auth.event

/**
 * Event payload published when a goods owner (client) is created.
 *
 * Contact details (email/phone/fax) are deliberately omitted: no consumer needs them, and the
 * outbox is a durable log that is also the future AI "recent activity" feed.
 */
data class ClientCreatedEvent(
    val clientId: Long,
    val name: String,
    val number: String,
    val code: String,
    val tenantId: Long,
)

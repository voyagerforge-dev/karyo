package com.karyo.auth.event

/** Event payload published when a goods owner is deactivated. */
data class ClientDeactivatedEvent(
    val clientId: Long,
    val number: String,
    val tenantId: Long,
)

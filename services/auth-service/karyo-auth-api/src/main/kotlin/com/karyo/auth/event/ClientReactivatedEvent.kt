package com.karyo.auth.event

/** Event payload published when a goods owner is reactivated. */
data class ClientReactivatedEvent(
    val clientId: Long,
    val number: String,
    val tenantId: Long,
)

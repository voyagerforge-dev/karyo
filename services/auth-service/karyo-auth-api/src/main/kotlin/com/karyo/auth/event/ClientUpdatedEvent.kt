package com.karyo.auth.event

/** Event payload published when a goods owner's mutable details change. `number` is immutable. */
data class ClientUpdatedEvent(
    val clientId: Long,
    val name: String,
    val number: String,
    val tenantId: Long,
)

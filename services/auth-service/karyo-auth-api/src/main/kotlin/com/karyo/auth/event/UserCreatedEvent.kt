package com.karyo.auth.event

/**
 * Event payload published when a new user is created via the auth-service API.
 * Serialized as-is into the dormant `outbox_events` log by OutboxService; the live readers
 * are the webhook relay and the copilot's recent-activity tool.
 */
data class UserCreatedEvent(
    val userId: String,
    val username: String,
    val email: String,
    val tenantId: Long,
    val roles: List<String>,
)

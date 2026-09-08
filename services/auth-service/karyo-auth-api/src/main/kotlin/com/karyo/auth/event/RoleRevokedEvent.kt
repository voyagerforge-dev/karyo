package com.karyo.auth.event

/**
 * Event payload published when a role is revoked from a user.
 * Serialized as-is into the dormant `outbox_events` log by OutboxService; the live readers
 * are the webhook relay and the copilot's recent-activity tool.
 */
data class RoleRevokedEvent(
    val userId: String,
    val username: String,
    val roleName: String,
    val tenantId: Long,
)

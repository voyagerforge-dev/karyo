package com.karyo.auth.dto

/**
 * Response DTO representing a user from Keycloak.
 * No validation annotations — this is output only.
 */
data class UserResponse(
    val id: String,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val enabled: Boolean,
    val roles: List<String> = emptyList(),
    val tenantCode: String? = null,
    val warehouseId: String? = null,
    val createdTimestamp: Long? = null,
)

package com.karyo.auth.dto

import jakarta.validation.constraints.Email

/**
 * Request DTO for updating an existing user in Keycloak.
 * All fields are nullable — only provided fields are updated (partial update).
 */
data class UpdateUserRequest(
    @field:Email(message = "Email must be a valid email address")
    val email: String? = null,

    val firstName: String? = null,

    val lastName: String? = null,

    val warehouseId: String? = null,
)

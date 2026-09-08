package com.karyo.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

/**
 * Request DTO for creating a new user in Keycloak.
 * Validated at the REST resource layer via Bean Validation.
 *
 * Both authorization-relevant inputs — [clientId] and [principalKind] — are required and
 * nullable-typed on purpose. A non-null Kotlin primitive would let Jackson supply `0L` for an
 * omitted `clientId`, silently provisioning the user under the SYS system client, and an absent
 * `principalKind` would have to be defaulted or inherited from the caller. Both are rejected
 * here and re-checked against the caller's authority server-side.
 */
data class CreateUserRequest(
    @field:NotBlank(message = "Username is required")
    @field:Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    val username: String,

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Email must be a valid email address")
    val email: String,

    @field:NotBlank(message = "First name is required")
    val firstName: String,

    @field:NotBlank(message = "Last name is required")
    val lastName: String,

    @field:NotBlank(message = "Password is required")
    val password: String,

    val roles: List<String> = emptyList(),

    @field:NotNull(message = "Goods owner is required")
    @field:PositiveOrZero(message = "Client ID must be zero or greater")
    val clientId: Long?,

    @field:NotNull(message = "Principal kind is required")
    @field:Pattern(
        regexp = "ops|owner",
        message = "Principal kind must be 'ops' or 'owner'",
    )
    val principalKind: String?,

    val warehouseId: String? = null,

    val forcePasswordChange: Boolean = true,
)

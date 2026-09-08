package com.karyo.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request DTO for admin-triggered password reset.
 */
data class ResetPasswordRequest(
    @field:NotBlank(message = "New password is required")
    @field:Size(min = 8, message = "Password must be at least 8 characters")
    val newPassword: String,

    val temporary: Boolean = true,
)

package com.karyo.auth.dto

import com.karyo.auth.vo.ClientState
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class ClientResponse(
    val id: Long,
    val name: String,
    val number: String,
    val code: String,
    val email: String,
    val phone: String,
    val fax: String,
    val state: ClientState,
    val isSystemClient: Boolean,
)

data class CreateClientRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:NotBlank @field:Size(max = 64) val number: String,
    @field:Size(max = 64) val code: String = "",
    @field:Email @field:Size(max = 255) val email: String = "",
    @field:Size(max = 64) val phone: String = "",
    @field:Size(max = 64) val fax: String = "",
)

/** `number` is the business key and is intentionally absent — it cannot be changed. */
data class UpdateClientRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:Size(max = 64) val code: String = "",
    @field:Email @field:Size(max = 255) val email: String = "",
    @field:Size(max = 64) val phone: String = "",
    @field:Size(max = 64) val fax: String = "",
)

/**
 * `client_id` values found in operational data with no matching client row. Expected to be
 * empty; a non-empty list means a Keycloak token references a client that was never created
 * (design D2 — no foreign keys enforce this, so it is reported rather than prevented).
 */
data class ClientConsistencyReport(
    val danglingClientIds: List<Long>,
    val checkedAt: Instant,
)

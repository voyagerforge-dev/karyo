package com.karyo.auth.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** Where an effective system-property value was resolved from (SC16 fallback ladder). */
enum class PropertySource {
    /** A stored row for the exact (non-zero) client. */
    CLIENT,

    /** The stored client-0 (instance-wide) row. */
    SYSTEM,

    /** MicroProfile config (application.yaml / env var) for the same key. */
    CONFIG,

    /** Nothing stored or configured — the code-owned catalog default. */
    DEFAULT,
}

/**
 * One row of the system-properties screen: either a catalog key with its resolved effective
 * value, or a stored non-catalog row. [type]/[group]/[description]/[defaultValue] carry the
 * code-owned catalog metadata where the key is known ([type] is null for non-catalog rows).
 */
data class SystemPropertyView(
    val key: String,
    val context: String? = null,
    val clientId: Long,
    val value: String?,
    val source: PropertySource,
    val type: String? = null,
    val group: String? = null,
    val description: String? = null,
    val defaultValue: String? = null,
    /** Write-only catalog key: [value] in the effective view is the literal mask, never the raw value. */
    val secret: Boolean = false,
    /** False = operator-controlled: a goods-owner principal's PUT/DELETE on this key is refused (403). */
    val ownerWritable: Boolean = true,
)

/** Upsert body for `PUT /api/v1/system-properties/{key}`. */
data class UpsertSystemPropertyRequest(
    @field:NotNull @field:Size(max = 2000) val value: String,
    @field:Size(max = 255) val context: String? = null,
    @field:Size(max = 2000) val description: String? = null,
    /** SYS (ops) principals may target a specific goods owner; an OWNER writes its own. */
    val clientId: Long? = null,
)

package com.karyo.common.exception

import java.time.Instant

/**
 * RFC 7807 Problem Details response body.
 * Used by all exception mappers across all services.
 * Fields per api-standards.md: type, title, status, detail, instance, traceId, timestamp.
 */
data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String? = null,
    val traceId: String? = null,
    val timestamp: Instant = Instant.now(),
)

package com.karyo.ai.proposal

import java.time.Instant

data class ActionProposal(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val summary: String,
    val params: Map<String, Any?>,
    val createdAt: Instant,
    /** The authenticated user (TenantContext.username) who created this proposal. */
    val owner: String,
)

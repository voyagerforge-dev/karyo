package com.karyo.webhooks.api.v1.dto

/** Returned ONLY on POST (create) — includes the secret. Never returned again. */
data class CreatedSubscriptionResponse(
    val id: Long,
    val name: String,
    val targetUrl: String,
    val eventTypes: List<String>,
    val active: Boolean,
    /** HMAC signing secret — shown once on creation; store it securely. */
    val secret: String,
)

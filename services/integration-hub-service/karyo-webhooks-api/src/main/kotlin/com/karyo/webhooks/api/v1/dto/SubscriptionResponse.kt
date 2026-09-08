package com.karyo.webhooks.api.v1.dto

/** Subscription view returned by list/get/update — secret is never included. */
data class SubscriptionResponse(
    val id: Long,
    val name: String,
    val targetUrl: String,
    val eventTypes: List<String>,
    val active: Boolean,
)

package com.karyo.webhooks.api.v1.dto

data class CreateSubscriptionRequest(
    val name: String,
    val targetUrl: String,
    val eventTypes: List<String>,
    val active: Boolean = true,
)

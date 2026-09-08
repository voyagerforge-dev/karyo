package com.karyo.webhooks.api.v1.dto

data class DeliveryResponse(
    val id: Long,
    val subscriptionId: Long,
    val eventType: String,
    val status: String,
    val attempts: Int,
    val lastResponseCode: Int?,
    val lastError: String?,
    val nextAttemptAt: String?,
    val deliveredAt: String?,
    val created: String,
)

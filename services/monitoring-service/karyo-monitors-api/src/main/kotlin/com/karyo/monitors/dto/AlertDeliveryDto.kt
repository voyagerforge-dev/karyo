package com.karyo.monitors.dto

/**
 * Mirrors `com.karyo.monitors.delivery.AlertDelivery` for the `/api/v1/alert-deliveries` screen
 * (clone of the webhook `DeliveryResponse` shape). `status` is a plain string, not the
 * `AlertDeliveryStatus` enum: that enum lives in `karyo-monitors-core`, and `api` never depends
 * on `core`.
 */
data class AlertDeliveryDto(
    val id: Long,
    val alertId: Long,
    val channelKey: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: String,
    val lastError: String?,
    val created: String,
)

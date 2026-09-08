package com.karyo.webhooks.dto

import com.fasterxml.jackson.databind.JsonNode

/** Canonical webhook payload delivered to subscribers (mirrors DomainEvent shape). */
data class WebhookEnvelope(
    val eventId: String,
    val eventType: String,
    val occurredAt: String,
    val tenantId: Long,
    val aggregateType: String,
    val aggregateId: Long,
    val data: JsonNode,
)

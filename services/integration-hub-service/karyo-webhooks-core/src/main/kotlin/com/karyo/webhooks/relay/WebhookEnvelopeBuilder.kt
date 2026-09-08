package com.karyo.webhooks.relay

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.events.outbox.OutboxEvent
import com.karyo.webhooks.dto.WebhookEnvelope
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class WebhookEnvelopeBuilder(private val mapper: ObjectMapper) {
    fun build(event: OutboxEvent, deliveryId: String): WebhookEnvelope =
        WebhookEnvelope(
            eventId = deliveryId,
            eventType = event.eventType,
            occurredAt = event.created.toString(),
            tenantId = event.tenantId,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            data = mapper.readTree(event.payload),
        )
}

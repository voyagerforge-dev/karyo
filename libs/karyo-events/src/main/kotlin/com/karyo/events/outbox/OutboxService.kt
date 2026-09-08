package com.karyo.events.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class OutboxService(
    private val repository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    fun publish(aggregateType: String, aggregateId: Long, eventType: String, payload: Any, tenantId: Long) {
        val event = OutboxEvent().apply {
            this.aggregateType = aggregateType
            this.aggregateId = aggregateId
            this.eventType = eventType
            this.payload = objectMapper.writeValueAsString(payload)
            this.tenantId = tenantId
            this.published = false
        }
        repository.persist(event)
    }
}

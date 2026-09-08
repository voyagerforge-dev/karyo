package com.karyo.events

import java.time.Instant
import java.util.UUID

/**
 * Canonical domain event envelope per system-architecture.md Section 3.2.
 *
 * Reserved for future external publication - no production code constructs one today.
 * Internal notifications are synchronous CDI events, and [com.karyo.events.outbox.OutboxService]
 * writes the bare payload into the dormant `outbox_events` log rather than wrapping it here.
 * The webhook relay's `WebhookEnvelope` mirrors this shape when it builds a delivery from an
 * outbox row. Topic naming `karyo.{service}.{entity}.{event-type}` is likewise reserved.
 */
data class DomainEvent<T>(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Instant = Instant.now(),
    val source: String,
    val correlationId: String? = null,
    val tenantId: Long,
    val schemaVersion: Int = 1,
    val payload: T,
)

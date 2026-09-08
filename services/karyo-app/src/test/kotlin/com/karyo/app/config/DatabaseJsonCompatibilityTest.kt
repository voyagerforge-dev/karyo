package com.karyo.app.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.events.outbox.OutboxEvent
import com.karyo.webhooks.domain.model.WebhookSubscription
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Stored JSONB is a compatibility contract, independent of REST-only Jackson customizers. */
@QuarkusTest
class DatabaseJsonCompatibilityTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Test
    @TestTransaction
    fun `raw JSON remains an object and existing database JSON can still be read`() {
        val payload = """{"text":"café","nested":{"enabled":true},"values":[1,null,"quoted\"value"]}"""
        val event = OutboxEvent().apply {
            aggregateType = "JsonCompatibility"
            eventType = "json.compatibility"
            this.payload = payload
        }
        entityManager.persist(event)
        entityManager.flush()
        assertThat(entityManager.createNativeQuery(
            "select jsonb_typeof(payload) from karyo.outbox_events where id = :id",
            String::class.java,
        ).setParameter("id", event.id).singleResult).isEqualTo("object")

        val stored = entityManager.createNativeQuery(
            "select cast(payload as text) from karyo.outbox_events where id = :id",
            String::class.java,
        ).setParameter("id", event.id).singleResult as String
        assertThat(objectMapper.readTree(stored)).isEqualTo(objectMapper.readTree(payload))
        println("JSONB outbox_events.payload after Hibernate write: $stored")

        // Write the pre-existing persisted format directly, then load it through Hibernate.
        val legacy = """{"legacy":true,"count":12.5,"text":"back\\slash"}"""
        entityManager.createNativeQuery(
            "update karyo.outbox_events set payload = cast(:payload as jsonb) where id = :id",
        ).setParameter("payload", legacy).setParameter("id", event.id).executeUpdate()
        entityManager.clear()
        val loaded = entityManager.find(OutboxEvent::class.java, event.id)
        assertThat(objectMapper.readTree(loaded.payload)).isEqualTo(objectMapper.readTree(legacy))
        println("JSONB legacy outbox_events.payload read through Hibernate: ${loaded.payload}")
    }

    @Test
    @TestTransaction
    fun `typed string lists keep their JSON array storage and reload legacy arrays`() {
        val events = listOf("StockCreated", "quoted\"value", "back\\slash", "café")
        val subscription = WebhookSubscription().apply {
            name = "JSON-${UUID.randomUUID()}"
            targetUrl = "https://example.invalid/unused"
            secret = "synthetic-json-test-only"
            eventTypes = events
            active = false
        }
        entityManager.persist(subscription)
        entityManager.flush()
        val stored = entityManager.createNativeQuery(
            "select cast(event_types as text) from karyo.webhook_subscription where id = :id",
            String::class.java,
        ).setParameter("id", subscription.id).singleResult as String
        assertThat(objectMapper.readTree(stored)).isEqualTo(objectMapper.valueToTree(events))
        println("JSONB webhook_subscription.event_types after Hibernate write: $stored")

        val legacy = """["AmountChanged","legacy\"value","café"]"""
        entityManager.createNativeQuery(
            "update karyo.webhook_subscription set event_types = cast(:events as jsonb) where id = :id",
        ).setParameter("events", legacy).setParameter("id", subscription.id).executeUpdate()
        entityManager.clear()
        val loaded = entityManager.find(WebhookSubscription::class.java, subscription.id)
        assertThat(loaded.eventTypes).containsExactly("AmountChanged", "legacy\"value", "café")
        println("JSONB legacy webhook_subscription.event_types read through Hibernate: ${loaded.eventTypes}")
    }
}

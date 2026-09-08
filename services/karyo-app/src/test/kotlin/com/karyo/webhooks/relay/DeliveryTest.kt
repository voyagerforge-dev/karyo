package com.karyo.webhooks.relay

import com.karyo.events.outbox.OutboxEvent
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class DeliveryTest {
    @Inject lateinit var delivery: WebhookDeliveryScheduler
    @Inject lateinit var subs: WebhookSubscriptionRepository
    @Inject lateinit var deliveries: WebhookDeliveryRepository
    @Inject lateinit var stub: StubReceiverResource
    @Inject lateinit var outboxRepo: OutboxEventRepository
    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081") lateinit var port: String

    private fun base() = "http://localhost:$port/test-receiver"

    @BeforeEach
    fun clearStub() {
        stub.headers.clear()
    }

    @Transactional
    fun seed(path: String, eventId: Long): Long {
        val s = WebhookSubscription().apply {
            clientId = 1; name = "s"; targetUrl = "${base()}/$path"
            secret = "secret"; eventTypes = listOf("*"); active = true
        }
        subs.persist(s)
        val d = WebhookDelivery().apply {
            subscriptionId = s.id!!; tenantId = 1; outboxEventId = eventId
            eventType = "PickConfirmed"; status = DeliveryStatus.PENDING
            nextAttemptAt = Instant.now().minusSeconds(1)
        }
        deliveries.persist(d)
        return d.id!!
    }

    @Transactional fun reload(id: Long): WebhookDelivery = deliveries.findById(id)!!

    @Test
    fun `successful delivery marks DELIVERED and sends a signature header`() {
        val id = seed("ok", 1000)
        delivery.runOnce()
        val d = reload(id)
        assertEquals(DeliveryStatus.DELIVERED, d.status)
        assertEquals(200, d.lastResponseCode)
        assertTrue(stub.headers["x-karyo-signature"]?.startsWith("sha256=") == true)
        assertNotNull(stub.headers["x-karyo-timestamp"])
        assertNotNull(d.deliveredAt) // Fix 4: verify timestamp is set on success
    }

    @Test
    fun `failing delivery increments attempts and reschedules as FAILED`() {
        val id = seed("fail", 1001)
        delivery.runOnce()
        val d = reload(id)
        assertEquals(DeliveryStatus.FAILED, d.status)
        assertEquals(1, d.attempts)
        assertEquals(500, d.lastResponseCode)
        assertTrue(d.nextAttemptAt.isAfter(Instant.now()))
    }

    @Test
    fun `delivery dead-letters after max attempts`() {
        val id = seed("fail", 1002)
        // drive attempts to max by forcing nextAttemptAt into the past each run
        repeat(8) {
            forceDue(id)
            delivery.runOnce()
        }
        assertEquals(DeliveryStatus.DEAD, reload(id).status)
    }

    /** Fix 4: covers OutboxReader.findById + WebhookEnvelopeBuilder.build with a real outbox row. */
    @Test
    fun `delivery with real outbox event builds envelope from source row and delivers`() {
        val outboxId = seedOutboxEvent()
        val deliveryId = seedDeliveryFor(outboxId, "ok")
        delivery.runOnce()
        val d = reload(deliveryId)
        assertEquals(DeliveryStatus.DELIVERED, d.status)
        assertEquals(200, d.lastResponseCode)
        assertNotNull(d.deliveredAt)
    }

    @Transactional
    fun seedOutboxEvent(): Long {
        val ev = OutboxEvent().apply {
            aggregateType = "Pick"; aggregateId = 99L
            eventType = "PickConfirmed"; tenantId = 1L
            payload = """{"picked":true}"""; published = false
        }
        outboxRepo.persist(ev)
        return ev.id!!
    }

    @Transactional
    fun seedDeliveryFor(outboxId: Long, path: String): Long {
        val s = WebhookSubscription().apply {
            clientId = 1; name = "real-body-sub"; targetUrl = "${base()}/$path"
            secret = "secret"; eventTypes = listOf("*"); active = true
        }
        subs.persist(s)
        val d = WebhookDelivery().apply {
            subscriptionId = s.id!!; tenantId = 1; outboxEventId = outboxId
            eventType = "PickConfirmed"; status = DeliveryStatus.PENDING
            nextAttemptAt = Instant.now().minusSeconds(1)
        }
        deliveries.persist(d)
        return d.id!!
    }

    @Transactional
    fun forceDue(id: Long) { deliveries.findById(id)!!.nextAttemptAt = Instant.now().minusSeconds(1) }
}

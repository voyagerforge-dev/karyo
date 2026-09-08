package com.karyo.webhooks.repository

import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.domain.model.WebhookSubscription
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.TestTransaction
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class WebhookRepositoryTest {
    @Inject lateinit var subs: WebhookSubscriptionRepository
    @Inject lateinit var deliveries: WebhookDeliveryRepository
    @Inject lateinit var cursor: FanoutCursorRepository

    private fun sub(client: Long, active: Boolean = true): WebhookSubscription =
        WebhookSubscription().apply {
            clientId = client; name = "s"; targetUrl = "https://x.test/h"
            secret = "shh"; eventTypes = listOf("*"); this.active = active
        }

    @Test @TestTransaction
    fun `findActive returns only active subscriptions`() {
        // Use ID-scoped assertions so committed data from HTTP tests doesn't affect the count.
        val s1 = sub(1, active = true).also { subs.persist(it) }
        val s2 = sub(1, active = false).also { subs.persist(it) }
        val active = subs.findActive()
        assertTrue(active.any { it.id == s1.id }, "active sub should appear in findActive")
        assertFalse(active.any { it.id == s2.id }, "inactive sub must NOT appear in findActive")
    }

    @Test @TestTransaction
    fun `findDue returns PENDING past next_attempt_at`() {
        val s = sub(1).also { subs.persist(it) }
        deliveries.persist(WebhookDelivery().apply {
            subscriptionId = s.id!!; tenantId = 1; outboxEventId = 10; eventType = "E"
            status = DeliveryStatus.PENDING; nextAttemptAt = Instant.now().minusSeconds(1)
        })
        deliveries.persist(WebhookDelivery().apply {
            subscriptionId = s.id!!; tenantId = 1; outboxEventId = 11; eventType = "E"
            status = DeliveryStatus.PENDING; nextAttemptAt = Instant.now().plusSeconds(600)
        })
        // Filter by subscriptionId so committed deliveries from other tests don't inflate the count.
        assertEquals(1, deliveries.findDue(50).count { it.subscriptionId == s.id!! })
    }

    @Test @TestTransaction
    fun `cursor advances`() {
        val start = cursor.current()
        cursor.advance(start + 5)
        assertEquals(start + 5, cursor.current())
    }
}

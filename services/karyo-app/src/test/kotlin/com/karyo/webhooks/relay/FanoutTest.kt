package com.karyo.webhooks.relay

import com.karyo.events.outbox.OutboxService
import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.repository.FanoutCursorRepository
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import io.quarkus.panache.common.Page
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class FanoutTest {
    @Inject lateinit var fanout: WebhookFanoutScheduler
    @Inject lateinit var subs: WebhookSubscriptionRepository
    @Inject lateinit var deliveries: WebhookDeliveryRepository
    @Inject lateinit var outbox: OutboxService
    @Inject lateinit var cursorRepo: FanoutCursorRepository
    @Inject lateinit var outboxReader: OutboxReader

    /**
     * Advance the cursor past all events committed by prior tests so [fanout.runOnce] only
     * sees events written during THIS test method.  Returns the cursor position that was set,
     * which the idempotency test uses to rewind to (rather than rewinding to 0 and re-scanning
     * prior tests' events, which would create spurious deliveries for other tests' subscriptions
     * and pollute the shared delivery table).
     */
    @Transactional
    fun setUpCursor(): Long {
        val maxId = outboxReader.find("order by id desc").page(Page.ofSize(1)).firstResult()?.id ?: 0L
        cursorRepo.advance(maxId)
        return maxId
    }

    @BeforeEach fun setUp() { setUpCursor() }

    /** Commits a subscription and returns its generated id. */
    @Transactional
    fun seedSub(client: Long, patterns: List<String>): Long {
        val sub = WebhookSubscription().apply {
            clientId = client; name = "s"; targetUrl = "https://x.test/h"
            secret = "shh"; eventTypes = patterns; active = true
        }
        subs.persist(sub)
        return sub.id!!
    }

    @Test
    fun `fan-out creates a delivery for a matching active subscription in the same tenant`() {
        // Isolated tenant IDs (7801/7802) prevent leakage from other tests in the shared DB.
        val subId = seedSub(7801L, listOf("DeliveryOrder*"))
        writeEvent("DeliveryOrder", 100L, "DeliveryOrderStateChanged", 7801L)
        writeEvent("GoodsReceipt", 200L, "GoodsReceiptStateChanged", 7801L)
        // Different tenant — must NOT be fanned out to the 7801 subscription.
        writeEvent("DeliveryOrder", 101L, "DeliveryOrderStateChanged", 7802L)

        fanout.runOnce()

        assertEquals(
            1L,
            countDeliveriesForSub(subId),
            "only the tenant-7801 DeliveryOrder* event should fan out",
        )
    }

    /**
     * SYS (client 0) has no goods of its own — a `clientId=0` subscription row (however it got
     * there) must never receive a delivery, even for a SYS-attributed event. This is the
     * defense-in-depth guard in the scheduler; the real gate is refusing registration in the
     * first place (see `SubscriptionResourceTest`).
     */
    @Test
    fun `a clientId-0 subscription never receives a delivery, even for a SYS-attributed event`() {
        val subId = seedSub(0L, listOf("*"))
        writeEvent("Client", 900L, "ClientUpdated", 0L)

        fanout.runOnce()

        assertEquals(
            0L,
            countDeliveriesForSub(subId),
            "a clientId=0 (SYS) subscription must never receive a delivery",
        )
    }

    @Test
    fun `fan-out is idempotent across runs`() {
        // Tenant 7803 is isolated. Cursor is rewound to the setUp() baseline (not to global 0)
        // to avoid re-scanning prior-test events, which would create spurious deliveries for
        // other tests' committed subscriptions and pollute the delivery table for downstream tests.
        val subId = seedSub(7803L, listOf("*"))
        val basePosition = currentCursorPos()
        writeEvent("DeliveryOrder", 300L, "DeliveryOrderStateChanged", 7803L)
        fanout.runOnce()
        val after1 = countDeliveriesForSub(subId)
        // Rewind to just before this test's events; idempotent unique index must prevent
        // a duplicate delivery when the same event is re-scanned.
        rewindCursorTo(basePosition)
        fanout.runOnce()
        assertEquals(after1, countDeliveriesForSub(subId), "re-scanning must not duplicate deliveries")
    }

    @Transactional
    fun writeEvent(aggType: String, aggId: Long, type: String, tenant: Long) =
        outbox.publish(aggType, aggId, type, mapOf("k" to "v"), tenant)

    /** Count deliveries scoped to a specific subscription to avoid whole-table fragility. */
    @Transactional
    fun countDeliveriesForSub(subId: Long): Long =
        deliveries.count("subscriptionId = ?1", subId)

    @Transactional fun currentCursorPos(): Long = cursorRepo.current()

    /** Set cursor unconditionally (test-only rewind; production code only advances). */
    @Transactional
    fun rewindCursorTo(pos: Long) {
        val c = cursorRepo.find("id = 1").firstResult() ?: return
        c.lastOutboxId = pos
    }
}

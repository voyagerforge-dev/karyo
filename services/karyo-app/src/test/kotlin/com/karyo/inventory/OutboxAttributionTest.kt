package com.karyo.inventory

import com.karyo.events.outbox.OutboxEvent
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.vo.StockState
import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.relay.WebhookFanoutScheduler
import com.karyo.webhooks.repository.FanoutCursorRepository
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Sibling of [JournalAttributionTest], for the outbox instead of the journal: an outbox row's
 * `tenantId` records **whose goods the event is about**, not who performed the write.
 *
 * This is not a cosmetic field. The v1.5 webhook relay
 * ([WebhookFanoutScheduler]) compares a subscription's owner `clientId` directly against the
 * event's `tenantId`, so publishing under the acting principal (ops staff = client 0 / SYS)
 * silently drops every notification a goods owner subscribed to for its own goods.
 */
@QuarkusTest
class OutboxAttributionTest {

    @Inject
    lateinit var outboxEvents: OutboxEventRepository

    @Inject
    lateinit var fanout: WebhookFanoutScheduler

    @Inject
    lateinit var subscriptions: WebhookSubscriptionRepository

    @Inject
    lateinit var deliveries: WebhookDeliveryRepository

    @Inject
    lateinit var cursor: FanoutCursorRepository

    // ── helpers ─────────────────────────────────────────────────────────────

    /** An ops principal must name the owner explicitly — it has no ambient client to infer. */
    private fun createUnitLoad(label: String, owner: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"clientId":$owner,"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$OA_LOCATION_ID,"storageLocationName":"OA-LOC"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, productNumber: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$OA_ITEM_DATA_ID,"itemDataNumber":"$productNumber",""" +
                    """"amount":$amount,"unitLoadId":$unitLoadId,"state":${StockState.ON_STOCK.code}}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun adjust(stockUnitId: Long, newAmount: Int, activityCode: String) =
        given().contentType(ContentType.JSON)
            .body("""{"newAmount":$newAmount,"activityCode":"$activityCode"}""")
            .`when`().post("/api/v1/stock-units/$stockUnitId/adjust")
            .then().statusCode(200)

    private fun amountChangedEvent(stockUnitId: Long): OutboxEvent =
        outboxEvents.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "StockUnit", stockUnitId, "AmountChanged",
        ).firstResult()!!

    /**
     * Parks the fanout cursor past every event committed so far, so [WebhookFanoutScheduler.runOnce]
     * only considers rows this test writes. Mirrors [com.karyo.webhooks.relay.FanoutTest].
     */
    @Transactional
    fun parkCursor() {
        cursor.advance(outboxEvents.find("order by id desc").firstResult()?.id ?: 0L)
    }

    @Transactional
    fun seedSubscription(owner: Long, name: String): Long {
        val sub = WebhookSubscription().apply {
            clientId = owner
            this.name = name
            targetUrl = "https://oa.test/hook"
            secret = "shh"
            eventTypes = listOf("AmountChanged")
            active = true
        }
        subscriptions.persist(sub)
        return sub.id!!
    }

    /**
     * Retires the subscription once asserted. It lives in the shared test DB, and an active
     * client-1 wildcard-ish subscription would otherwise keep matching other tests' events.
     */
    @Transactional
    fun deactivate(subId: Long) {
        subscriptions.findById(subId)?.active = false
    }

    @Transactional
    fun runFanout(): Int = fanout.runOnce()

    private fun deliveriesFor(subId: Long, eventId: Long): Long =
        deliveries.count("subscriptionId = ?1 and outboxEventId = ?2", subId, eventId)

    // ── tests ───────────────────────────────────────────────────────────────

    /**
     * The rule: an OPS principal (client 0 / SYS) adjusting client 1's stock must publish the
     * outbox row under client 1 — the owner of the goods the event describes.
     */
    @Test
    @TestSecurity(user = "opsuser", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ]
    )
    fun `outbox event is attributed to the goods owner, not the acting ops principal`() {
        val suffix = System.nanoTime().toString().takeLast(6)
        val unitLoadId = createUnitLoad("UL-OA-$suffix", owner = GOODS_OWNER)
        val stockUnitId = createStock(unitLoadId, "OA-$suffix", amount = 20.0)

        adjust(stockUnitId, newAmount = 12, activityCode = "OA-ADJUST")

        assertThat(amountChangedEvent(stockUnitId).tenantId)
            .`as`("outbox tenantId must name whose goods the event is about (owner $GOODS_OWNER), not the actor (ops, client 0)")
            .isEqualTo(GOODS_OWNER)
    }

    /**
     * The user-visible consequence, end to end: goods owner 1 subscribes to stock-amount
     * changes, ops staff move owner 1's stock, and the fanout must produce a delivery for
     * that subscription. Under the bug the event carried `tenantId = 0`, the
     * `s.clientId != e.tenantId` guard skipped it, and the owner was never notified.
     */
    @Test
    @TestSecurity(user = "opsuser", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(
        claims = [
            Claim(key = "client_id", value = "0"),
            Claim(key = "tenant_code", value = "SYS"),
            Claim(key = "principal_kind", value = "ops"),
        ]
    )
    fun `goods owner's webhook subscription receives a delivery for a movement performed by ops staff`() {
        val suffix = System.nanoTime().toString().takeLast(6)
        val unitLoadId = createUnitLoad("UL-OAW-$suffix", owner = GOODS_OWNER)
        val stockUnitId = createStock(unitLoadId, "OAW-$suffix", amount = 20.0)
        val subId = seedSubscription(GOODS_OWNER, "oa-sub-$suffix")

        // Park the cursor AFTER the fixtures so only the adjust event below is fanned out.
        parkCursor()
        adjust(stockUnitId, newAmount = 12, activityCode = "OAW-ADJUST")

        runFanout()

        val eventId = amountChangedEvent(stockUnitId).id!!
        val created = deliveriesFor(subId, eventId)
        deactivate(subId)

        assertThat(created)
            .`as`("owner $GOODS_OWNER subscribed to AmountChanged must be notified about its own goods moving")
            .isEqualTo(1L)
    }

    companion object {
        /** The goods owner (3PL customer) whose stock the ops principal handles. */
        private const val GOODS_OWNER = 1L

        /** Any existing location/item id — these tests assert attribution, not placement. */
        private const val OA_LOCATION_ID = 1L
        private const val OA_ITEM_DATA_ID = 1L
    }
}

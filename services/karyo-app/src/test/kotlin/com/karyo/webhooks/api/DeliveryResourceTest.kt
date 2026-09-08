package com.karyo.webhooks.api

import com.karyo.webhooks.domain.model.DeliveryStatus
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.repository.WebhookDeliveryRepository
import com.karyo.webhooks.repository.WebhookSubscriptionRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class DeliveryResourceTest {
    @Inject lateinit var deliveries: WebhookDeliveryRepository
    @Inject lateinit var subscriptions: WebhookSubscriptionRepository

    @Transactional
    fun seedDead(tenant: Long): Long {
        // FK: webhook_delivery.subscription_id → webhook_subscription.id, so create a real sub first
        val sub = WebhookSubscription().apply {
            clientId = tenant; name = "test-sub"; targetUrl = "https://test.example/h"
            secret = "s"; eventTypes = listOf("*"); active = true
        }
        subscriptions.persist(sub)
        val d = WebhookDelivery().apply {
            subscriptionId = sub.id!!; tenantId = tenant; outboxEventId = null
            eventType = "PickConfirmed"; status = DeliveryStatus.DEAD; attempts = 8
            nextAttemptAt = Instant.now()
        }
        deliveries.persist(d)
        return d.id!!
    }

    @Test @TestSecurity(user = "admin", roles = ["integration-admin"])
    fun `redeliver resets a DEAD delivery to PENDING`() {
        val id = seedDead(0) // @TestSecurity default clientId is 0
        given().post("/api/v1/webhook-deliveries/$id/redeliver").then().statusCode(202)
        given().get("/api/v1/webhook-deliveries?status=PENDING")
            .then().statusCode(200).body("find { it.id == $id }.status", equalTo("PENDING"))
    }

    @Test @TestSecurity(user = "admin", roles = ["integration-admin"])
    fun `invalid status query param returns 400`() {
        given().get("/api/v1/webhook-deliveries?status=BOGUS").then().statusCode(400)
    }

    @Test @TestSecurity(user = "admin", roles = ["integration-admin"])
    fun `redeliver non-existent delivery returns 404`() {
        given().post("/api/v1/webhook-deliveries/999999/redeliver").then().statusCode(404)
    }

    @Test @TestSecurity(user = "viewer", roles = ["inventory-read"])
    fun `non-admin cannot read the log`() {
        given().get("/api/v1/webhook-deliveries").then().statusCode(403)
    }
}

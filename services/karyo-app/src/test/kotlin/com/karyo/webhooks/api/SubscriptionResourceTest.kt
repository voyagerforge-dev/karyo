package com.karyo.webhooks.api

import com.karyo.webhooks.repository.WebhookDeliveryRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.emptyOrNullString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `client_id` is a required claim on every create-path test below: SYS (client 0, the default
 * when the claim is omitted) has no goods to subscribe to and is refused at registration (see
 * the dedicated SYS-refusal test) — a real owner clientId keeps the rest of these tests testing
 * what they say they test, rather than incidentally exercising the SYS guard.
 */
@QuarkusTest
class SubscriptionResourceTest {

    @Inject
    lateinit var deliveryRepository: WebhookDeliveryRepository

    @Test
    @TestSecurity(user = "admin", roles = ["integration-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `create returns the secret once, list omits it`() {
        val id = given().contentType(ContentType.JSON)
            .body("""{"name":"acme","targetUrl":"https://acme.test/h","eventTypes":["*"]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(201)
            .body("secret", not(emptyOrNullString()))
            .extract().path<Int>("id")

        given().get("/api/v1/webhook-subscriptions")
            .then().statusCode(200)
            .body("find { it.id == $id }.secret", nullValue())
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    fun `non-admin cannot create`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"x","targetUrl":"https://x.test/h","eventTypes":["*"]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["integration-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `invalid event type pattern is rejected`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"x","targetUrl":"https://x.test/h","eventTypes":[]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["integration-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `test ping creates a delivery`() {
        val id = given().contentType(ContentType.JSON)
            .body("""{"name":"p","targetUrl":"https://p.test/h","eventTypes":["*"]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(201)
            .extract().path<Int>("id")

        given().post("/api/v1/webhook-subscriptions/$id/test")
            .then().statusCode(anyOf(equalTo(202), equalTo(200)))

        // Assert a delivery row was actually persisted for this subscription (ID-scoped)
        val deliveries = deliveryRepository.findByClientAndFilter(
            clientId = 1L, subscriptionId = id.toLong(), status = null, limit = 10
        )
        assertTrue(
            deliveries.any { it.subscriptionId == id.toLong() },
            "Expected at least one WebhookDelivery row for subscriptionId=$id"
        )
    }

    /**
     * SYS (client 0) owns no goods — it has nothing to subscribe to. Real callers are goods
     * owners (or ops staff acting explicitly on an owner's behalf elsewhere); a bare SYS token
     * registering a subscription is refused rather than silently persisted as a `clientId=0`
     * row the scheduler would then have to guard against (see `FanoutTest`).
     */
    @Test
    @TestSecurity(user = "sysuser", roles = ["integration-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "0"), Claim(key = "principal_kind", value = "ops")])
    fun `SYS principal cannot register a subscription`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"sys-sub","targetUrl":"https://sys.test/h","eventTypes":["*"]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(400)
    }

    /**
     * `user-admin` is a distinct realm role from `integration-admin` — a principal holding the
     * former but not the latter (e.g. a user-management operator with no integration duties)
     * must still be refused. Only ADMIN's composite (or a direct grant, as `manager` now has)
     * satisfies this gate.
     */
    @Test
    @TestSecurity(user = "useradmin-only", roles = ["user-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `user-admin without integration-admin cannot list subscriptions`() {
        given().get("/api/v1/webhook-subscriptions")
            .then().statusCode(403)
    }

    // ── SSRF guard tests ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = ["integration-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `SSRF guard rejects EC2 metadata IP`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"ssrf-meta","targetUrl":"http://169.254.169.254/latest/meta-data","eventTypes":["*"]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin", roles = ["integration-admin"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `SSRF guard rejects localhost`() {
        given().contentType(ContentType.JSON)
            .body("""{"name":"ssrf-lo","targetUrl":"http://localhost/x","eventTypes":["*"]}""")
            .post("/api/v1/webhook-subscriptions")
            .then().statusCode(400)
    }
}

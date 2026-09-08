package com.karyo.stocktaking

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * REST integration test for [com.karyo.stocktaking.api.v1.StocktakingResource].
 *
 * Verifies:
 * 1. POST /count-sessions → 201 with a session containing the expected order.
 * 2. GET /count-orders/{id}?view=entry → 200 with blind view (no "plannedAmount" field).
 * 3. Unauthenticated POST /count-sessions → 401.
 *
 * clientId 2501 reserved for this suite.
 * Uses inventory-read + inventory-write (existing realm roles — no --reset-db needed).
 */
@QuarkusTest
class StocktakingResourceTest {

    // ── Seeding helpers ────────────────────────────────────────────────────────

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String, locationId: Long, locationName: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"$locationName"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates an ON_STOCK stock unit. Item-unit name suffix ≤8 chars (field max=20). */
    private fun createStock(ulId: Long, amount: Double): Long {
        val suffix = System.nanoTime().toString().takeLast(8)
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":2501001,"itemDataNumber":"SRT-$suffix",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Seeds layout + stock for the happy-path test. */
    private fun seedLocation(): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-SRT-$ns")
        val areaId = createArea("AREA-SRT-$ns")
        val locName = "LOC-SRT-$ns"
        val locationId = createLocation(locName, ltId, areaId)
        val ulId = createUnitLoad("UL-SRT-$ns", locationId, locName)
        createStock(ulId, 20.0)
        return locationId
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-write", "inventory-read", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2501"), Claim(key = "tenant_code", value = "SRT-TEST")])
    fun `start a count then fetch the blind entry view (no planned amount)`() {
        val locationId = seedLocation()

        // POST /count-sessions → 201, returns the summary (no nested orders)
        val sessionResp = given().contentType(ContentType.JSON)
            .body("""{"locationIds":[$locationId]}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(201)
            .extract().jsonPath()

        val sessionId = sessionResp.getLong("id")
        // GET /count-sessions/{id} → the full graph, to reach the generated order id.
        val orderId = given().`when`().get("/api/v1/count-sessions/$sessionId")
            .then().statusCode(200)
            .extract().jsonPath().getLong("orders[0].id")
        assertThat(orderId).isGreaterThan(0)

        // GET /count-orders/{id}?view=entry → 200, body must NOT contain "plannedAmount"
        val entryBody = given()
            .`when`().get("/api/v1/count-orders/$orderId?view=entry")
            .then().statusCode(200)
            .extract().body().asString()

        assertThat(entryBody).doesNotContain("plannedAmount")
        assertThat(entryBody).doesNotContain("countedAmount")
        // lines are present (blind, item identity only)
        assertThat(entryBody).contains("lineId")
        assertThat(entryBody).contains("itemDataNumber")
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["inventory-write", "inventory-read", "layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2501"), Claim(key = "tenant_code", value = "SRT-TEST")])
    fun `count order review view carries the owning session id`() {
        val locationId = seedLocation()

        val sessionResp = given().contentType(ContentType.JSON)
            .body("""{"locationIds":[$locationId]}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(201)
            .extract().jsonPath()

        val sessionId = sessionResp.getLong("id")
        assertThat(sessionId).isGreaterThan(0)
        val graph = given().`when`().get("/api/v1/count-sessions/$sessionId")
            .then().statusCode(200)
            .extract().jsonPath()
        val orderId = graph.getLong("orders[0].id")

        // GET /count-orders/{id} (review view, no ?view=entry) must carry sessionId
        // so FE query-invalidation (sessionKey(order.sessionId)) is not a silent no-op.
        given()
            .`when`().get("/api/v1/count-orders/$orderId")
            .then().statusCode(200)
            .body("sessionId", org.hamcrest.Matchers.equalTo(sessionId.toInt()))

        // Also present on the order already embedded in the session graph.
        assertThat(graph.getLong("orders[0].sessionId")).isEqualTo(sessionId)
    }

    @Test
    fun `start rejects unauthenticated`() {
        given().contentType(ContentType.JSON)
            .body("""{"locationIds":[999]}""")
            .`when`().post("/api/v1/count-sessions")
            .then().statusCode(401)
    }
}

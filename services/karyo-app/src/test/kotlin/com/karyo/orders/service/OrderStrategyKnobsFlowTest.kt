package com.karyo.orders.service

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration test verifying that the new OrderStrategy picking knobs
 * (preferMatching, completeHandling, enforceLot) flow end-to-end:
 * strategy API -> entity columns -> reservation call -> selection behaviour.
 *
 * Two tests:
 * 1. DEFAULT strategy (all knobs neutral) still reserves correctly — regression guard.
 * 2. A strategy with completeHandling=2 (AMOUNT_FIRST_PLUS) successfully reserves from
 *    a complete unit load when one is available.
 */
@QuarkusTest
class OrderStrategyKnobsFlowTest {

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Knobs Flow Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStrategy(name: String, completeHandling: Int = 0, preferMatching: Boolean = false, enforceLot: Boolean = false): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","completeHandling":$completeHandling,"preferMatching":$preferMatching,"enforceLot":$enforceLot}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStrategyWithShortPick(name: String, shortPickMode: String, shortfallStrategy: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","shortPickMode":"$shortPickMode","shortfallStrategy":"$shortfallStrategy"}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStrategyWithPickDifference(name: String, pickDifferenceStrategy: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","pickDifferenceStrategy":"$pickDifferenceStrategy"}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStrategyWithPackout(name: String, packoutStrategy: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","packoutStrategy":"$packoutStrategy"}""")
            .`when`().post("/api/v1/order-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createOrder(itemDataId: Long, amount: Double, strategyId: Long? = null): Long {
        val strategyJson = if (strategyId != null) ""","orderStrategyId":$strategyId""" else ""
        return given().contentType(ContentType.JSON)
            .body("""{"customerName":"Knobs Test Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]$strategyJson}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `DEFAULT strategy (all knobs neutral) still reserves stock correctly - regression guard`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("KF-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("KF-SKU-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-KF-DEF-$suffix")
        createStock(ulId, productId, "KF-SKU-$suffix", 200.0)

        // Order with no strategyId => DEFAULT strategy (all knobs false/0 — neutral behaviour)
        val orderId = createOrder(productId, 75.0)
        val releaseJson = given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
            .extract().jsonPath()

        assertThat(releaseJson.getInt("order.state")).isEqualTo(300)   // PROCESSABLE
        assertThat(releaseJson.getDouble("order.lines[0].reservedAmount")).isEqualTo(75.0)
        assertThat(releaseJson.getList<Any>("shortages")).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `strategy knobs round-trip via API and flow through to reservation`() {
        val suffix = System.nanoTime()

        // Create a strategy with the new knobs and verify they round-trip
        val strategyId = createStrategy("COMPLETE-PLUS-$suffix", completeHandling = 2)
        val strategyJson = given()
            .`when`().get("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200).extract().jsonPath()
        assertThat(strategyJson.getInt("completeHandling")).isEqualTo(2)
        assertThat(strategyJson.getBoolean("preferMatching")).isFalse()
        assertThat(strategyJson.getBoolean("enforceLot")).isFalse()
        // Unset short-pick knobs carry their defaults
        assertThat(strategyJson.getString("shortPickMode")).isEqualTo("FOLLOW_UP_THEN_SUBSTITUTE")
        assertThat(strategyJson.getString("shortfallStrategy")).isEqualTo("PARTIAL_SHIP")
        // Unset packout knob carries its default
        assertThat(strategyJson.getString("packoutStrategy")).isEqualTo("ONE_TO_ONE")

        // Seed stock and place an order using this strategy
        val itemUnitId = createItemUnit("KF-IU2-${suffix.toString().takeLast(8)}")
        val productId = createProduct("KF-SKU2-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-KF-CH2-$suffix")
        createStock(ulId, productId, "KF-SKU2-$suffix", 100.0)

        val orderId = createOrder(productId, 60.0, strategyId)
        val releaseJson = given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
            .extract().jsonPath()

        // completeHandling=2 (AMOUNT_FIRST_PLUS): finds the complete 100-unit UL which covers 60
        assertThat(releaseJson.getInt("order.state")).isEqualTo(300)   // PROCESSABLE
        assertThat(releaseJson.getDouble("order.lines[0].reservedAmount")).isEqualTo(60.0)
        assertThat(releaseJson.getList<Any>("shortages")).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `short-pick knobs round-trip via API`() {
        val suffix = System.nanoTime()
        val strategyId = createStrategyWithShortPick(
            "SHORTPICK-$suffix",
            shortPickMode = "SUBSTITUTE_ONLY",
            shortfallStrategy = "PARTIAL_SHIP",
        )
        val strategyJson = given()
            .`when`().get("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200).extract().jsonPath()
        assertThat(strategyJson.getString("shortPickMode")).isEqualTo("SUBSTITUTE_ONLY")
        assertThat(strategyJson.getString("shortfallStrategy")).isEqualTo("PARTIAL_SHIP")
        // Unset pick-difference knob carries its default
        assertThat(strategyJson.getString("pickDifferenceStrategy")).isEqualTo("LEAVE")
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pickDifferenceStrategy knob round-trips via API`() {
        val suffix = System.nanoTime()
        val strategyId = createStrategyWithPickDifference("PICKDIFF-$suffix", pickDifferenceStrategy = "WRITE_OFF")
        val strategyJson = given()
            .`when`().get("/api/v1/order-strategies/$strategyId")
            .then().statusCode(200).extract().jsonPath()
        assertThat(strategyJson.getString("pickDifferenceStrategy")).isEqualTo("WRITE_OFF")
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packoutStrategy knob round-trips via API and defaults to ONE_TO_ONE`() {
        val suffix = System.nanoTime()

        // Explicit value round-trips
        val explicitId = createStrategyWithPackout("PACKOUT-$suffix", packoutStrategy = "MULTI_TO_ONE")
        val explicitJson = given()
            .`when`().get("/api/v1/order-strategies/$explicitId")
            .then().statusCode(200).extract().jsonPath()
        assertThat(explicitJson.getString("packoutStrategy")).isEqualTo("MULTI_TO_ONE")

        // A strategy created without packoutStrategy carries the default
        val defaultId = createStrategyWithPickDifference("PACKOUT-DEF-$suffix", pickDifferenceStrategy = "LEAVE")
        val defaultJson = given()
            .`when`().get("/api/v1/order-strategies/$defaultId")
            .then().statusCode(200).extract().jsonPath()
        assertThat(defaultJson.getString("packoutStrategy")).isEqualTo("ONE_TO_ONE")
    }
}

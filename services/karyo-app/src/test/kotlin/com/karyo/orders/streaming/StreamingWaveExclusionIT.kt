package com.karyo.orders.streaming

import com.karyo.orders.spi.OrderReleasePort
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Task 1 (order streaming, orders FOSS side): [com.karyo.orders.messaging.DefaultOrderReleasePort]'s
 * wave-candidate exclusions for STREAM-mode orders (spec ruling 5) -- an order whose effective
 * mode is STREAM (a STREAM strategy with no override, or an explicit `releaseModeOverride`
 * "STREAM") is never wave-eligible, a STREAM strategy is never auto-waved even with
 * `waveAutoRelease = true`, and explicit-ids wave assignment refuses an order streaming has
 * already claimed -- but NOT an un-attempted order on a STREAM strategy (ruling 5's
 * manual-wins carve-out). Also covers the per-order `releaseModeOverride` validation (422)
 * and a MANUAL-strategy regression (still wave-eligible, unaffected by this task).
 *
 * Fixture helpers lifted from `WaveSchedulerIT` (product+stock seeding, `extensionProperties`
 * strategy seeding via `POST /api/v1/order-strategies`).
 *
 * Every test uses a distinct client_id so parallel/-repeated runs never collide.
 */
@QuarkusTest
class StreamingWaveExclusionIT {

    @Inject
    lateinit var orderReleasePort: OrderReleasePort

    // ── Fixture helpers (lifted from WaveSchedulerIT) ───────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON).body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"number":"$number","name":"P","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Seeds a product with [stockAmount] of stock on its own unit load. Returns the product/item id. */
    private fun seedProductWithStock(tag: String, stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "SWE-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("SWE-$tag-UL-$s")
        createStock(ul, pid, num, stockAmount)
        return pid
    }

    /** Strategy with an arbitrary raw JSON `extensionProperties` body -- unlike WaveSchedulerIT's
     *  `createStrategy`, this task's fixtures need the `releaseMode` key too. */
    private fun createStrategy(tag: String, extensionPropertiesJson: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"SWE-$tag-${System.nanoTime()}","extensionProperties":$extensionPropertiesJson}""")
            .`when`().post("/api/v1/order-strategies").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Raw response (not pre-asserted) so callers can check success and failure status codes alike. */
    private fun createOrderRaw(itemDataId: Long, amount: Double, strategyId: Long, releaseModeOverride: String?): Response {
        val overrideJson = releaseModeOverride?.let { ""","releaseModeOverride":"$it"""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Cust","zipCode":"1","city":"City","orderStrategyId":$strategyId$overrideJson,""" +
                    """"lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders")
    }

    private fun createOrder(itemDataId: Long, amount: Double, strategyId: Long, releaseModeOverride: String? = null): Long =
        createOrderRaw(itemDataId, amount, strategyId, releaseModeOverride)
            .then().statusCode(201).extract().jsonPath().getLong("id")

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9501")])
    fun `STREAM strategy, un-attempted order -- excluded from wave candidates and auto-release, but explicit-ids wave assignment still allowed`() {
        val clientId = 9501L
        val strategyId = createStrategy("STREAM", """{"releaseMode":"STREAM","waveAutoRelease":true}""")
        val item = seedProductWithStock("STREAM", 100.0)
        val orderId = createOrder(item, 10.0, strategyId)

        assertThat(orderReleasePort.findWaveEligible(strategyId, clientId, 10)).isEmpty()
        assertThat(orderReleasePort.waveEnabledStrategies().map { it.strategyId }).doesNotContain(strategyId)

        // Un-attempted STREAM-strategy order CAN still be waved by explicit operator choice (ruling 5).
        orderReleasePort.assignToWave(listOf(orderId), 1L, clientId)
        assertThat(orderReleasePort.waveIdOf(orderId, clientId)).isEqualTo(1L)
        orderReleasePort.clearWave(listOf(orderId), clientId)
        assertThat(orderReleasePort.waveIdOf(orderId, clientId)).isNull()
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9502")])
    fun `MANUAL strategy, order with explicit lowercase STREAM override -- echoed uppercase, excluded, wave assignment refused`() {
        val clientId = 9502L
        val strategyId = createStrategy("MANUAL-OV", """{"releaseMode":"MANUAL"}""")
        val item = seedProductWithStock("MANUAL-OV", 100.0)

        val response = createOrderRaw(item, 10.0, strategyId, "stream")
        response.then().statusCode(201)
        val orderId = response.jsonPath().getLong("id")
        assertThat(response.jsonPath().getString("releaseModeOverride")).isEqualTo("STREAM")

        assertThat(orderReleasePort.findWaveEligible(null, clientId, 10).map { it.orderId }).doesNotContain(orderId)

        assertThatThrownBy { orderReleasePort.assignToWave(listOf(orderId), 1L, clientId) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9503")])
    fun `unparsable releaseModeOverride is rejected at create`() {
        val strategyId = createStrategy("BAD-OV", """{"releaseMode":"MANUAL"}""")
        val item = seedProductWithStock("BAD-OV", 100.0)

        createOrderRaw(item, 10.0, strategyId, "later").then().statusCode(422)
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9504")])
    fun `MANUAL strategy, no override -- still wave-eligible (regression)`() {
        val clientId = 9504L
        val strategyId = createStrategy("MANUAL-REG", """{"releaseMode":"MANUAL"}""")
        val item = seedProductWithStock("MANUAL-REG", 100.0)
        val orderId = createOrder(item, 10.0, strategyId)

        assertThat(orderReleasePort.findWaveEligible(strategyId, clientId, 10).map { it.orderId }).contains(orderId)
    }
}

package com.karyo.fulfillment.spi

import com.karyo.fulfillment.exception.FulfillmentException
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Order streaming, Task 3: [PickReleasePort] is the release seam the paid streaming scheduler
 * (Task 5) will call from a `@Scheduled` thread that never primes the ambient `TenantContext` --
 * exactly what this test reproduces by calling the port directly, off any HTTP request, with NO
 * `@TestSecurity`/`@OidcSecurity`-primed context on the calling thread (those annotations only
 * establish a security/tenant context for the REST calls made through RestAssured below, not for
 * a direct in-JVM method call on the test thread itself).
 *
 * Fixture helpers lifted from `WaveSchedulerIT` (REST-seeded product/stock/staging/order).
 */
@QuarkusTest
class PickReleasePortIT {

    @Inject
    lateinit var pickReleasePort: PickReleasePort

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

    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PRP-PACK_STAGING-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PRP-PACK_STAGING-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PRP-PACK_STAGING-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun createOrder(itemDataId: Long, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Cust","zipCode":"1","city":"City",""" +
                    """"lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")

    /** Seeds a product with [stockAmount] of stock on its own unit load, an order for the same
     *  amount, releases it (PROCESSABLE, fully reserved) via REST, and returns the order id. */
    private fun seedProcessableOrder(tag: String, amount: Double): Long {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "PRP-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("PRP-$tag-UL-$s")
        createStock(ul, pid, num, amount)
        val orderId = createOrder(pid, amount)
        given().`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
        return orderId
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9521")])
    fun `direct call with no ambient priming releases the order and returns created PickOrder ids`() {
        val clientId = 9521L
        val orderId = seedProcessableOrder("OK", 100.0)

        val pickOrderIds = pickReleasePort.releaseToPicking(orderId, clientId)

        assertThat(pickOrderIds).isNotEmpty

        val orderState = given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200).extract().jsonPath().getInt("state")
        assertThat(orderState).isEqualTo(500) // STARTED

        val pickOrders = given().`when`().get("/api/v1/pick-orders")
            .then().statusCode(200).extract().jsonPath().getList<Map<String, Any>>("$")
        val match = pickOrders.singleOrNull { (it["deliveryOrderId"] as Number?)?.toLong() == orderId }
        assertThat(match).isNotNull
    }

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9521")])
    fun `direct call for a foreign clientId throws NotFound`() {
        val orderId = seedProcessableOrder("FOREIGN", 100.0)

        assertThatThrownBy { pickReleasePort.releaseToPicking(orderId, 9522L) }
            .isInstanceOf(FulfillmentException.NotFound::class.java)
    }
}

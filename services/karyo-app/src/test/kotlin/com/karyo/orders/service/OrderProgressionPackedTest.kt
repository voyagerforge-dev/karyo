package com.karyo.orders.service

import com.karyo.orders.spi.OrderProgressionPort
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test

/**
 * Integration test (REAL beans) for the [OrderProgressionPort.markPacked] write seam: seeds and
 * releases an order, drives it to PICKED, then advances it to PACKED via the SPI and verifies the
 * new state through REST.
 */
@QuarkusTest
class OrderProgressionPackedTest {

    @Inject
    lateinit var progression: OrderProgressionPort

    // ── REST seeding helpers (lifted from OrderProgressionStartedTest) ──────

    private fun createItemUnit(name: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Progression Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double): Long =
        given()
            .contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createOrder(itemDataId: Long, amount: Double): ValidatableResponse =
        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"Progression Test Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)

    private fun seedProductWithStock(suffix: Long, stockAmount: Double): Long {
        val itemUnitId = createItemUnit("PR-${suffix.toString().takeLast(10)}")
        val number = "PR-SKU-$suffix"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-PR-$suffix")
        createStock(unitLoadId, productId, number, stockAmount)
        return productId
    }

    /** Seeds a product with 100 stock, creates an order for 60, releases it (full coverage). Returns order id. */
    private fun seedAndReleaseOrderFor60(): Long {
        val suffix = System.nanoTime()
        val productId = seedProductWithStock(suffix, stockAmount = 100.0)
        val orderId = createOrder(productId, amount = 60.0).extract().jsonPath().getLong("id")
        given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200)
        return orderId
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `markPacked advances a picked order to PACKED`() {
        val orderId = seedAndReleaseOrderFor60()

        progression.markStarted(orderId, 1L)
        progression.markPicked(orderId, 1L)
        progression.markPacked(orderId, 1L)

        given().`when`().get("/api/v1/delivery-orders/$orderId")
            .then().statusCode(200).body("state", `is`(650))
    }
}

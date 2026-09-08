package com.karyo.orders.service

import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration test (REAL beans) for the [DeliveryOrderLookup] in-process read seam: seeds a
 * product with stock, creates + releases an order, then reads it back through the SPI and
 * asserts the per-line reservation slices used for pick generation.
 */
@QuarkusTest
class DeliveryOrderLookupTest {

    @Inject
    lateinit var lookup: DeliveryOrderLookup

    @Inject
    lateinit var tenantContext: TenantContext

    // ── REST seeding helpers (lifted from OrderReservationFlowTest) ──────────

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
            .body("""{"number":"$number","name":"Lookup Test Product","itemUnitId":$itemUnitId}""")
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
            .body("""{"customerName":"Lookup Test Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)

    private fun seedProductWithStock(suffix: Long, stockAmount: Double): Pair<Long, Long> {
        val itemUnitId = createItemUnit("LU-${suffix.toString().takeLast(10)}")
        val number = "LU-SKU-$suffix"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-LU-$suffix")
        val stockUnitId = createStock(unitLoadId, productId, number, stockAmount)
        return productId to stockUnitId
    }

    /** Seeds a product with 100 stock, creates an order for 60, releases it (full coverage). Returns order id. */
    private fun seedAndReleaseOrderFor60(): Long {
        val suffix = System.nanoTime()
        val (productId, _) = seedProductWithStock(suffix, stockAmount = 100.0)
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
    fun `findForPicking returns the order with reservation slices`() {
        val orderId = seedAndReleaseOrderFor60()

        tenantContext.clientId = 1L
        val result = lookup.findForPicking(orderId)

        assertThat(result).isNotNull
        assertThat(result!!.orderId).isEqualTo(orderId)
        assertThat(result.state).isEqualTo(300)
        assertThat(result.lines).hasSize(1)
        val line = result.lines.first()
        assertThat(line.reservations).isNotEmpty
        assertThat(line.reservations.sumOf { it.amount.toDouble() }).isEqualTo(60.0)
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findForPicking returns null for an unknown order`() {
        tenantContext.clientId = 1L
        assertThat(lookup.findForPicking(999_999_999L)).isNull()
    }
}

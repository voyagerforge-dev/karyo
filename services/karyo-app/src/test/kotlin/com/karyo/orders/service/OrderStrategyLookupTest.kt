package com.karyo.orders.service

import com.karyo.orders.spi.OrderStrategyLookup
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Integration test (REAL beans) for the [OrderStrategyLookup] in-process read seam: seeds a
 * plain order (no custom strategy) and reads its resolved picking knobs back through the SPI,
 * asserting the DEFAULT strategy values that short-pick recovery will run under.
 */
@QuarkusTest
class OrderStrategyLookupTest {

    @Inject
    lateinit var lookup: OrderStrategyLookup

    @Inject
    lateinit var tenantContext: TenantContext

    // ── REST seeding helpers (lifted from DeliveryOrderLookupTest) ──────────

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
            .body("""{"number":"$number","name":"Strategy Lookup Product","itemUnitId":$itemUnitId}""")
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

    private fun createOrder(itemDataId: Long, amount: Double): Long =
        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"Strategy Lookup Customer","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Seeds a product with stock and a plain order (no custom strategy). Returns the order id. */
    private fun seedOrder(): Long {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("SL-${suffix.toString().takeLast(10)}")
        val number = "SL-SKU-$suffix"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-SL-$suffix")
        createStock(unitLoadId, productId, number, amount = 100.0)
        return createOrder(productId, amount = 60.0)
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findPickingStrategy returns the resolved default knobs for a plain order`() {
        val orderId = seedOrder()
        tenantContext.clientId = 1L

        val view = lookup.findPickingStrategy(orderId)
        assertThat(view).isNotNull
        assertThat(view!!.shortPickMode).isEqualTo("FOLLOW_UP_THEN_SUBSTITUTE")
        assertThat(view.shortfallStrategy).isEqualTo("PARTIAL_SHIP")
        assertThat(view.pickDifferenceStrategy).isEqualTo("LEAVE")
        assertThat(view.packoutStrategy).isEqualTo("ONE_TO_ONE")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `findPickingStrategy returns null for an unknown order`() {
        tenantContext.clientId = 1L
        assertThat(lookup.findPickingStrategy(999_999_999L)).isNull()
    }
}

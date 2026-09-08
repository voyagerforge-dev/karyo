package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockReserver
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
import java.math.BigDecimal

/**
 * Verifies the DefaultStockReserver behaviour after the StockReserver SPI was widened
 * to accept a ReservationRequest. Tests use the order release pathway (the only caller
 * of StockReserver) to exercise the full pipeline through the security filter.
 *
 * The key behaviours under test:
 * - reserve(ReservationRequest) correctly reserves the requested amount (default knobs).
 * - The widening is backward-compatible: the default-knobs path behaves identically to
 *   the former positional reserve(itemDataId, amount, useLockedStock).
 */
@QuarkusTest
class DefaultStockReserverTest {

    @Inject lateinit var stockReserver: StockReserver
    @Inject lateinit var tenantContext: TenantContext

    // ── Seed helpers ──────────────────────────────────────────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Reserver Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun stockReservedAmount(stockUnitId: Long): Double =
        given().`when`().get("/api/v1/stock-units/$stockUnitId")
            .then().statusCode(200).extract().jsonPath().getDouble("reservedAmount")

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * End-to-end: order release calls StockReserver.reserve(ReservationRequest) with
     * default knobs. Verifies that the widened SPI correctly reserves stock and that
     * the shortfall is zero (behavior-neutral compared to the old positional signature).
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `ReservationRequest with default knobs fully reserves available stock`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("RSV-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("RSV-SKU-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-RSV-$suffix")
        val stockUnitId = createStock(ulId, productId, "RSV-SKU-$suffix", 100.0)

        // Create and release an order — this calls DefaultStockReserver.reserve(ReservationRequest)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"Reserver Test","lines":[{"itemDataId":$productId,"amount":60.0}]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then().statusCode(201).extract().jsonPath().getLong("id")

        val releaseJson = given()
            .`when`().post("/api/v1/delivery-orders/$orderId/release")
            .then().statusCode(200).extract().jsonPath()

        // No shortfall: the ReservationRequest(default knobs) must reserve 60 from 100 on-stock units
        assertThat(releaseJson.getInt("order.state")).isEqualTo(300)   // PROCESSABLE
        assertThat(releaseJson.getDouble("order.lines[0].reservedAmount")).isEqualTo(60.0)
        assertThat(releaseJson.getList<Any>("shortages")).isEmpty()

        // Inventory side: reservedAmount incremented
        assertThat(stockReservedAmount(stockUnitId)).isEqualTo(60.0)
    }

    /**
     * `reserveOnStockUnit` (added for the picking-block sprint's Task 2 fix round — closes the
     * fulfillment top-up reservation-backing gap): a targeted reserve on a KNOWN stock unit,
     * bypassing 13-pass selection entirely.
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reserveOnStockUnit reserves the exact amount on the named stock unit and returns true`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("RSV-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("RSV-SKU-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-RSV-$suffix")
        val stockUnitId = createStock(ulId, productId, "RSV-SKU-$suffix", 50.0)
        tenantContext.clientId = 1L

        val ok = stockReserver.reserveOnStockUnit(stockUnitId, BigDecimal(30), "test-reserve-exact")

        assertThat(ok).isTrue
        assertThat(stockReservedAmount(stockUnitId)).isEqualTo(30.0)
    }

    /**
     * When the stock unit can no longer cover the requested amount (e.g. already partly reserved
     * by another order), `reserveOnStockUnit` returns `false` and does NOT partially reserve or
     * throw across the module boundary — the underlying `InventoryException.InsufficientStock` is
     * caught inside `DefaultStockReserver`, mirroring `reserve()`'s existing per-unit catch idiom.
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reserveOnStockUnit returns false and reserves nothing when availableAmount is insufficient`() {
        val suffix = System.nanoTime()
        val itemUnitId = createItemUnit("RSV-IU-${suffix.toString().takeLast(8)}")
        val productId = createProduct("RSV-SKU-$suffix", itemUnitId)
        val ulId = createUnitLoad("UL-RSV-$suffix")
        val stockUnitId = createStock(ulId, productId, "RSV-SKU-$suffix", 20.0)
        tenantContext.clientId = 1L
        // Reserve 15 of the 20 first (e.g. a different order's legitimate claim) -- only 5 left.
        assertThat(stockReserver.reserveOnStockUnit(stockUnitId, BigDecimal(15), "other-order")).isTrue

        val ok = stockReserver.reserveOnStockUnit(stockUnitId, BigDecimal(10), "test-reserve-exact")

        assertThat(ok).isFalse
        // Still exactly 15 -- the failed attempt reserved nothing, and the earlier reservation
        // (someone else's) was never touched or partially consumed.
        assertThat(stockReservedAmount(stockUnitId)).isEqualTo(15.0)
    }
}

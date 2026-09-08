package com.karyo.fulfillment.service

import com.karyo.fulfillment.FreeTierBatchPickFixture
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Task 2 (Bulk Allocation Sprint A, sort station): [BatchPickPort.pickedByLines] and
 * [BatchPickPort.cartByUnitLoad] - the sort-station reads that the wave module calls to derive
 * "what was picked, on which cart, for which order line". [FreeTierBatchPickFixture] creates the
 * batch work through the public fulfillment SPI rather than the commercial wave REST resource.
 *
 * Two deviations from the task brief's illustrative test body, both discovered empirically:
 *
 * 1. The brief's sketch located the batch PickOrder via `GET /api/v1/pick-orders` and read a
 *    `waveId` JSON field off each row -- `PickOrderResponse` carries no `waveId` field today
 *    (confirmed: neither `PickDtos.kt` nor `PickOrderResource.kt` expose it, and no other test
 *    reads one off that endpoint), and adding one is outside this task's file list. This test
 *    instead filters on `deliveryOrderId == null` alone, which is sufficient here: this test's
 *    tenant (client 9202) only ever has the one wave/batch order in play.
 * 2. The brief's sketch also injected `PickOrderRepository` directly to fetch the batch order
 *    up front (mirroring `WaveReleaseIT`'s pattern). That trips a genuine test-only staleness
 *    trap: a direct repository read of a `PickOrder` row BEFORE the REST-driven `confirmPick`
 *    calls mutate it leaves a stale, first-level-cached entity in this test method's own
 *    persistence context -- a LATER read of the SAME id (even via the production
 *    `batchPickPort.cartByUnitLoad` call, going through a differently-injected repository
 *    instance) returns that cached snapshot rather than the REST-committed row (confirmed via a
 *    throwaway debug print: `GET /pick-orders/{id}` correctly showed `state=600` post-confirm
 *    while `cartByUnitLoad(...).state` returned the stale pre-confirm `100` in the same run).
 *    This is a `@QuarkusTest`-thread persistence-context artifact, not a production bug -- a real
 *    caller reaches `cartByUnitLoad` from its own fresh request/EntityManager. The fix here is to
 *    resolve the batch order purely through REST (which always reads its own fresh state) and
 *    never touch it via direct JPA before the picks are confirmed.
 */
@QuarkusTest
class WavePickServiceSortReadsIT {
    @Inject
    lateinit var batchPickPort: BatchPickPort

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Free-tier fixture helpers ──────────────────────────────────────────

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

    private fun seedStagingArea(usage: String) {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"WR-$usage-${System.nanoTime()}","usages":["$usage"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"WR-$usage-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"WR-$usage-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Seeds a product with [stockAmount] of stock on its own unit load. Returns the product/item id. */
    private fun seedProductWithStock(tag: String, stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "WR-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("WR-$tag-UL-$s")
        createStock(ul, pid, num, stockAmount)
        return pid
    }

    private fun createOrder(itemDataId: Long, amount: Double, customerName: String, zipCode: String, city: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"$customerName","zipCode":"$zipCode","city":"$city",""" +
                    """"lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun confirmPick(pickId: Long, amount: Double) =
        given().contentType(ContentType.JSON)
            .body("""{"pickedAmount":$amount,"targetUnitLoadId":null}""")
            .`when`().post("/api/v1/picks/$pickId/confirm").then().statusCode(200)

    // ── Test-local helpers ───────────────────────────────────────────────

    private fun lineId(orderId: Long): Long =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getLong("lines[0].id")

    // ── Test ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "m",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9202")])
    fun `pickedByLines returns one slice per PICKED batch pick with the cart unit load, cartByUnitLoad resolves the cart`() {
        seedStagingArea("PACK_STAGING")
        seedStagingArea("SHIP_STAGING")
        val clientId = 9202L
        val pid = seedProductWithStock("sr", 100.0)
        val o1 = createOrder(pid, 20.0, "SR Co", "10001", "NYC")
        val o2 = createOrder(pid, 15.0, "SR Co", "10002", "Boston")
        val generated = FreeTierBatchPickFixture(batchPickPort, tenantContext)
            .generate(listOf(o1, o2), clientId, "PICK_ONLY")
        val waveId = generated.waveId
        val batchId = generated.result.batchPickOrderIds.single()
        val batch = given().`when`().get("/api/v1/pick-orders/$batchId").then().statusCode(200)
            .extract().jsonPath()
        val cartUl = batch.getLong("targetUnitLoadId")
        val picks = given().`when`().get("/api/v1/pick-orders/$batchId").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("picks")
        assertEquals(2, picks.size)

        // Nothing PICKED yet: no slices.
        assertTrue(batchPickPort.pickedByLines(listOf(lineId(o1), lineId(o2)), clientId).isEmpty())

        picks.forEach { confirmPick((it["id"] as Number).toLong(), (it["plannedAmount"] as Number).toDouble()) }

        val slices = batchPickPort.pickedByLines(listOf(lineId(o1), lineId(o2)), clientId)
        assertEquals(2, slices.size)
        assertEquals(setOf(lineId(o1), lineId(o2)), slices.map { it.deliveryOrderLineId }.toSet())
        assertTrue(slices.all { it.cartUnitLoadId == cartUl })
        assertTrue(slices.all { it.cartStockUnitId != null })
        assertEquals(35.0, slices.sumOf { it.pickedAmount.toDouble() })

        val cart = batchPickPort.cartByUnitLoad(cartUl, clientId)
        assertNotNull(cart)
        assertEquals(batchId, cart!!.pickOrderId)
        assertEquals(waveId, cart.waveId)
        assertEquals(600, cart.state)
        assertEquals(2, cart.slices.size)

        // Other tenant sees nothing.
        assertTrue(batchPickPort.pickedByLines(listOf(lineId(o1)), 9999).isEmpty())
        assertNull(batchPickPort.cartByUnitLoad(cartUl, 9999))
    }
}

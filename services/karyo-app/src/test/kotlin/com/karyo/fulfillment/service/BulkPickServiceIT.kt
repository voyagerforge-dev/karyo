package com.karyo.fulfillment.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.karyo.events.outbox.OutboxEvent
import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.fulfillment.FreeTierBatchPickFixture
import com.karyo.fulfillment.api.v1.dto.BulkConfirmRequest
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Bulk Allocation Sprint B, Task 3: [BulkPickService.bulkLines]/[BulkPickService.bulkConfirm].
 * Domain fixtures are created through free REST endpoints, then [FreeTierBatchPickFixture]
 * generates the batch PickOrder through the public fulfillment SPI.
 */
@QuarkusTest
class BulkPickServiceIT {

    @Inject
    lateinit var bulkPickService: BulkPickService

    @Inject
    lateinit var batchPickPort: BatchPickPort

    @Inject
    lateinit var waveActivityObserver: TestWaveActivityObserver

    /**
     * `PickOrderService.confirmPick` reads the ambient `TenantContext.clientId`, populated by
     * `TenantFilter`, a JAX-RS `ContainerRequestFilter` that runs only on an actual HTTP request.
     * This test calls `bulkPickService.bulkConfirm` directly as a CDI method (not via REST), so
     * the filter never runs and the ambient context is never primed by `@OidcSecurity` alone --
     * confirmed against the identical, already-established fix in `PickConfirmServiceTest`
     * (explicit `tenantContext.clientId = ...` before any direct call reaching `confirmPick`).
     * Test-fact correction to the brief's Step 5, which assumed the claim alone was sufficient.
     */
    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var outboxRepository: OutboxEventRepository

    @Inject
    lateinit var objectMapper: ObjectMapper

    /**
     * Defect-burndown-6 (row :2047, A7): reaching the "source stock unit is not visible to this
     * tenant" branch needs a direct row write. There is no supported route that produces it --
     * `POST /api/v1/stock-units/{id}/change-client` is refused by `OpenPickGuard` precisely
     * because an open pick still references the row -- so the repository is the only door.
     */
    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    /** Newest outbox row for aggregate "PickOrder"/[aggregateId] carrying [eventType], or null. */
    @Transactional
    fun outboxRowFor(aggregateId: Long, eventType: String): OutboxEvent? =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3 order by created desc",
            "PickOrder", aggregateId, eventType,
        ).firstResult()

    @BeforeEach
    fun clearBefore() {
        waveActivityObserver.events.clear()
    }

    @AfterEach
    fun reset() {
        waveActivityObserver.events.clear()
    }

    // ── Fixture helpers (copied verbatim from WaveReleaseIT) ────────────────

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
            .body("""{"name":"BP-$usage-${System.nanoTime()}","usages":["$usage"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"BP-$usage-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"BP-$usage-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Seeds a product with [stockAmount] of stock on its own unit load. Returns the product/item id. */
    private fun seedProductWithStock(tag: String, stockAmount: Double): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "BP-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("BP-$tag-UL-$s")
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

    private fun generateBatch(orderIds: List<Long>, pickMode: String) =
        FreeTierBatchPickFixture(batchPickPort, tenantContext).generate(orderIds, CLIENT_ID, pickMode)

    // ── Test-local helpers ───────────────────────────────────────────────

    /**
     * Reassigns [stockUnitId] to a foreign owner straight on the row. This simulates DATA
     * CORRUPTION, not a supported operation: an open pick whose source stock unit is no longer
     * visible to the pick's own tenant (deleted, purged, or reassigned out from under it). The
     * supported route refuses exactly this (`OpenPickGuard`), which is why the assertion below
     * has to manufacture the state directly.
     *
     * Returns the owner it displaced so the caller can put the row back: this suite shares one
     * database with every other test class in the module, so a deliberately corrupted row must
     * never outlive the assertion that needed it (see [restoreStockUnitOwner]).
     */
    @Transactional
    fun orphanStockUnit(stockUnitId: Long): Long {
        val su = stockUnitRepository.findById(stockUnitId) ?: error("stock unit $stockUnitId was not seeded")
        val previousOwner = su.clientId
        su.clientId = 9398L
        return previousOwner
    }

    /** Undoes [orphanStockUnit], leaving the shared DB as found. */
    @Transactional
    fun restoreStockUnitOwner(stockUnitId: Long, clientId: Long) {
        stockUnitRepository.findById(stockUnitId)?.clientId = clientId
    }

    private fun orderState(orderId: Long): Int =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200).extract().jsonPath().getInt("state")

    /** One SKU, 100 on one stock unit; three orders 20/15/5 so the bulk line is 40 over three slices. */
    private fun bulkWave(tag: String): Triple<Long, Long, List<Long>> {
        seedStagingArea("PACK_STAGING"); seedStagingArea("SHIP_STAGING")
        val pid = seedProductWithStock(tag, 100.0)
        val orders = listOf(
            createOrder(pid, 20.0, "$tag Co", "10001", "NYC"),
            createOrder(pid, 15.0, "$tag Co", "20002", "DC"),
            createOrder(pid, 5.0, "$tag Co", "30003", "LA"),
        )
        val generated = generateBatch(orders, "BULK")
        return Triple(generated.waveId, generated.result.batchPickOrderIds.single(), orders)
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9302")])
    fun `bulk lines aggregate by source stock unit and a full confirm fills every slice`() {
        val (_, batchId, orders) = bulkWave("bf")
        val lines = bulkPickService.bulkLines(batchId, 9302)
        assertEquals(1, lines.size)
        assertEquals(40.0, lines[0].plannedTotal.toDouble())
        assertEquals(3, lines[0].openSlices)
        assertEquals("A-01-01", lines[0].locationName)

        tenantContext.clientId = 9302L
        val out = bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(lines[0].sourceStockUnitId, BigDecimal(40)), 9302)
        assertEquals(3, out.filledSlices); assertEquals(0, out.shortSlices)
        assertTrue(bulkPickService.bulkLines(batchId, 9302).isEmpty(), "no open slices remain")
        orders.forEach { assertEquals(600, orderState(it), "every member order PICKED (line-scoped completion)") }
        assertEquals(3, waveActivityObserver.events.size)

        // bulkConfirm must publish its own outbox event -- aggregate "PickOrder"/batchId, the
        // exact event type BulkPickService.bulkConfirm publishes, carrying the confirm's own facts.
        val outboxRow = outboxRowFor(batchId, "pick.bulk-confirmed")
        assertNotNull(outboxRow, "pick.bulk-confirmed outbox event must be published")
        val payload = objectMapper.readTree(outboxRow!!.payload)
        assertEquals(40.0, payload.get("pickedAmount").asDouble())
        assertEquals(3, payload.get("filledSlices").asInt())
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9302")])
    fun `bulk lines group by source stock unit when a single order's reservation spans two stock units`() {
        seedStagingArea("PACK_STAGING"); seedStagingArea("SHIP_STAGING")
        val s = System.nanoTime()
        val iu = createItemUnit("IU-tw-${s.toString().takeLast(14)}")
        val num = "BP-tw-SKU-$s"
        val pid = createProduct(num, iu)
        val ulA = createUnitLoad("BP-tw-ULA-$s")
        val ulB = createUnitLoad("BP-tw-ULB-$s")
        val suA = createStock(ulA, pid, num, 20.0)
        val suB = createStock(ulB, pid, num, 20.0)

        // One order needs 30 -- more than either single stock unit holds by itself, so its own
        // reservation must genuinely split across both. FIFO (created ASC) fully consumes suA
        // (created first) before spilling onto suB for the remainder -- deterministic within a
        // single order's own selection, unlike relying on cross-order depletion timing.
        val o1 = createOrder(pid, 30.0, "TW Co", "10001", "NYC")
        val batchId = generateBatch(listOf(o1), "BULK").result.batchPickOrderIds.single()

        val lines = bulkPickService.bulkLines(batchId, 9302)
        assertEquals(
            setOf(suA, suB), lines.map { it.sourceStockUnitId }.toSet(),
            "precondition: the allocation must genuinely span two distinct source stock units",
        )
        assertEquals(2, lines.size)
        // Walk order: suA's slice was created before suB's spillover slice, so suA's group is the
        // first occurrence when walking picks by id ascending.
        assertEquals(suA, lines[0].sourceStockUnitId)
        assertEquals(20.0, lines[0].plannedTotal.toDouble(), "suA fully consumed")
        assertEquals(1, lines[0].openSlices)
        assertEquals(suB, lines[1].sourceStockUnitId)
        assertEquals(10.0, lines[1].plannedTotal.toDouble(), "the remainder spills onto suB")
        assertEquals(1, lines[1].openSlices)

        tenantContext.clientId = 9302L
        bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(suA, BigDecimal(20)), 9302)

        // suB's line is untouched by confirming suA -- exactly the second line remains, unchanged.
        val remaining = bulkPickService.bulkLines(batchId, 9302)
        assertEquals(1, remaining.size)
        assertEquals(suB, remaining[0].sourceStockUnitId)
        assertEquals(10.0, remaining[0].plannedTotal.toDouble())
        assertEquals(1, remaining[0].openSlices)
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9302")])
    fun `short confirm fills the head, tail is short or zero, reservations released, no follow-ups`() {
        val (_, batchId, orders) = bulkWave("bs")
        val line = bulkPickService.bulkLines(batchId, 9302).single()
        tenantContext.clientId = 9302L
        val out = bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(line.sourceStockUnitId, BigDecimal(27)), 9302)
        assertEquals(listOf(20.0, 7.0, 0.0), out.slices.map { it.picked.toDouble() })
        assertEquals(2, out.filledSlices); assertEquals(1, out.shortSlices)
        val picks = given().`when`().get("/api/v1/pick-orders/$batchId").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("picks")
        assertEquals(3, picks.size, "no follow-up picks minted")
        assertTrue(picks.all { (it["state"] as Number).toInt() == 600 })
        // Batch short-confirm stays consistent with the PICK_ONLY batch path: it never touches
        // DeliveryOrderLine.reservedAmount (a high-water mark, written only by reserveLine/cancel
        // -- see OrderService's own KDoc). The stock-unit side is the fact that actually moves:
        // 100 seeded, 27 moved to the cart (picked), 13 released back to available.
        val su = given().`when`().get("/api/v1/stock-units/${line.sourceStockUnitId}")
            .then().statusCode(200).extract().jsonPath()
        assertEquals(0.0, su.getDouble("reservedAmount"), "stock unit's reservation fully resolved (picked + released)")
        assertEquals(73.0, su.getDouble("amount"), "100 seeded - 27 picked to the cart")
        assertEquals(600, orderState(orders[2]), "zero-content member still reaches PICKED (Sprint C handles it)")
    }

    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9302")])
    fun `guards - not bulk, out of range, unknown stock unit, wrong tenant`() {
        val (_, batchId, _) = bulkWave("bg")
        val line = bulkPickService.bulkLines(batchId, 9302).single()
        assertThrows(FulfillmentException.InvalidPickConfirmation::class.java) {
            bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(line.sourceStockUnitId, BigDecimal(41)), 9302)
        }
        assertThrows(FulfillmentException.InvalidPickConfirmation::class.java) {
            bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(-1, BigDecimal(1)), 9302)
        }
        assertThrows(FulfillmentException.NotFound::class.java) { bulkPickService.bulkLines(batchId, 9999) }

        // A PICK_ONLY batch order is refused.
        val pid = seedProductWithStock("nb", 10.0)
        val o = createOrder(pid, 3.0, "NB Co", "10001", "NYC")
        val nonBulk = generateBatch(listOf(o), "PICK_ONLY").result.batchPickOrderIds.single()
        assertThrows(FulfillmentException.ValidationFailed::class.java) { bulkPickService.bulkLines(nonBulk, 9302) }
    }

    /**
     * Defect-burndown-6 (row :2047, A7): a bulk line whose source stock unit is missing from
     * [com.karyo.inventory.api.spi.StockUnitLookup.findByIds] is REFUSED, not fabricated. The
     * lookup's own contract says absence means unknown/deleted/foreign, so rendering `""` for
     * `locationName`/`unitLoadLabel` would send an operator to a location that does not exist.
     * See [orphanStockUnit] for why the corrupt state has to be manufactured directly.
     */
    @Test
    @TestSecurity(user = "op", roles = [
        "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
        "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
    ])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9302")])
    fun `bulk lines refuse a line whose source stock unit is no longer visible`() {
        val (_, batchId, _) = bulkWave("orphan")
        val suId = bulkPickService.bulkLines(batchId, 9302).single().sourceStockUnitId
        val realOwner = orphanStockUnit(suId)
        try {
            val ex = assertThrows(FulfillmentException.NotFound::class.java) { bulkPickService.bulkLines(batchId, 9302) }
            assertTrue(
                ex.message!!.contains("StockUnit") && ex.message!!.contains(suId.toString()),
                "the refusal must name the missing stock unit, was: ${ex.message}",
            )
        } finally {
            restoreStockUnitOwner(suId, realOwner)
        }
    }

    private companion object {
        const val CLIENT_ID = 9302L
    }
}

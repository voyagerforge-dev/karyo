package com.karyo.fulfillment.service

import com.karyo.fulfillment.FreeTierBatchPickFixture
import com.karyo.fulfillment.api.v1.dto.BulkConfirmRequest
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.fulfillment.spi.ConsolidationPackPort
import com.karyo.fulfillment.spi.MemberOrderRef
import com.karyo.fulfillment.spi.OpenContainerRequest
import com.karyo.fulfillment.spi.OpenGroupShipmentRequest
import com.karyo.fulfillment.spi.PackLineAllocation
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Bulk Allocation Sprint C, Task 3: [ConsolidationPackPort] -- group shipment, containers as
 * unit loads, cross-order lines, close/complete, and the CONSOLIDATION cancel restore.
 *
 * Domain rows and reservations are created through free REST endpoints. Batch picks are generated
 * through [BatchPickPort] by [FreeTierBatchPickFixture], so these fulfillment-port tests do not
 * require the commercial wave resource or sort station. Direct service and port calls still need
 * an explicitly primed [TenantContext] because `TenantFilter` runs only on HTTP requests.
 *
 * Every test uses its own `client_id`: the suite's DB is never reset between test methods.
 */
@QuarkusTest
class ConsolidationPackPortIT {

    @Inject
    lateinit var port: ConsolidationPackPort

    @Inject
    lateinit var bulkPickService: BulkPickService

    @Inject
    lateinit var batchPickPort: BatchPickPort

    @Inject
    lateinit var shippingLifecycleService: ShippingLifecycleService

    @Inject
    lateinit var tenantContext: TenantContext

    // -- Fixture helpers (private copies, per repo convention) ---------------

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

    private fun createStock(ulId: Long, itemDataId: Long, number: String, amount: Double, lot: String? = null): Long {
        val lotJson = if (lot == null) "" else ""","lotNumber":"$lot""""
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,""" +
                    """"unitLoadId":$ulId,"state":300$lotJson}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun seedStagingArea(usage: String) {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PP-$usage-${System.nanoTime()}","usages":["$usage"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PP-$usage-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PP-$usage-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Seeds a product with [stockAmount] of stock, optionally under [lot], on its own unit load. */
    private fun seedProductWithStock(tag: String, stockAmount: Double, lot: String? = null): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("IU${s.toString().takeLast(14)}")
        val num = "PP-$tag-SKU-$s"
        val pid = createProduct(num, iu)
        val ul = createUnitLoad("PP-$tag-UL-$s")
        createStock(ul, pid, num, stockAmount, lot)
        return pid
    }

    // -- Test-local helpers --------------------------------------------------

    private fun createOrderAt(itemDataId: Long, amount: Double, tag: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"$tag Co","street":"Main St","streetNumber":"1","zipCode":"10001","city":"NYC",""" +
                    """"country":"US","lines":[{"itemDataId":$itemDataId,"amount":$amount}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun lineId(orderId: Long): Long =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getLong("lines[0].id")

    private fun orderState(orderId: Long): Int =
        given().`when`().get("/api/v1/delivery-orders/$orderId").then().statusCode(200)
            .extract().jsonPath().getInt("state")

    /**
     * Deliberately read over REST, not through [StockUnitLookup] directly: a `@QuarkusTest` test
     * method runs inside ONE request context, so a non-transactional direct read shares one
     * Hibernate session with every earlier direct read in the same method and hands back the
     * first-level-cache copy of a StockUnit another transaction has since updated. Empirically
     * confirmed here -- the direct read still showed PICKED(600) after `closeContainer` had
     * committed PACKED(650). REST goes over the wire into a fresh request/session every time.
     */
    private fun stockRows(unitLoadId: Long): List<Map<String, Any>> =
        given().`when`().get("/api/v1/stock-units?unitLoadId=$unitLoadId&size=100").then().statusCode(200)
            .extract().jsonPath().getList("content")

    private fun stockStates(unitLoadId: Long) = stockRows(unitLoadId).map { (it["state"] as Number).toInt() }

    private fun lotsOn(unitLoadId: Long): Set<String?> =
        stockRows(unitLoadId).filter { (it["amount"] as Number).toDouble() > 0.0 }
            .map { it["lotNumber"] as String? }.toSet()

    private fun stockAmountOn(unitLoadId: Long): BigDecimal =
        stockRows(unitLoadId).fold(BigDecimal.ZERO) { acc, row -> acc + BigDecimal((row["amount"] as Number).toString()) }

    /** SHIP_STAGING area + one location; returns (locationId, locationName). PACK_STAGING seeded alongside. */
    private fun seedShipStagingAndReturn(): Pair<Long, String> {
        seedStagingArea("PACK_STAGING")
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PP-SHIP-${System.nanoTime()}","usages":["SHIP_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PP-LT-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        val name = "PP-DOCK-${System.nanoTime()}"
        val locId = given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")
        return locId to name
    }

    data class ReadyGroup(
        val waveId: Long,
        val groupId: Long,
        val cartUnitLoadId: Long,
        val orderIds: List<Long>,
        val lineIds: List<Long>,
        val pickIds: List<Long>,
        val cartStockUnitIds: List<Long>,
        val itemId: Long,
        val sku: String,
        val lotNumber: String?,
    )

    /**
     * Creates one bulk batch over two orders, then confirms the requested quantity onto its cart.
     * The fulfillment port accepts the caller-owned wave and group identifiers as opaque values,
     * so no commercial group setup is needed to exercise its mechanics.
     */
    private fun readyGroup(tag: String, confirmAmount: Int = 35, lot: String? = null): ReadyGroup {
        seedShipStagingAndReturn()
        val pid = seedProductWithStock(tag, 100.0, lot)
        val orders = listOf(createOrderAt(pid, 20.0, tag), createOrderAt(pid, 15.0, tag))
        val generated = FreeTierBatchPickFixture(batchPickPort, tenantContext)
            .generate(orders, CLIENT, "BULK")
        val batchId = generated.result.batchPickOrderIds.single()
        tenantContext.clientId = CLIENT
        val line = bulkPickService.bulkLines(batchId, CLIENT).single()
        bulkPickService.bulkConfirm(batchId, BulkConfirmRequest(line.sourceStockUnitId, BigDecimal(confirmAmount)), CLIENT)
        val cartUl = given().`when`().get("/api/v1/pick-orders/$batchId").then().statusCode(200)
            .extract().jsonPath().getLong("targetUnitLoadId")
        val lineIds = orders.map { lineId(it) }
        val slices = batchPickPort.pickedByLines(lineIds, CLIENT).sortedBy { it.pickId }
        return ReadyGroup(
            waveId = generated.waveId,
            groupId = generated.waveId,
            cartUnitLoadId = cartUl,
            orderIds = orders,
            lineIds = lineIds,
            pickIds = lineIds.map { lid -> slices.first { it.deliveryOrderLineId == lid }.pickId },
            cartStockUnitIds = lineIds.map { lid -> slices.first { it.deliveryOrderLineId == lid }.cartStockUnitId!! },
            itemId = line.itemDataId,
            sku = line.itemDataNumber,
            lotNumber = slices.first().lotNumber,
        )
    }

    // -- Tests ---------------------------------------------------------------

    @Test
    @TestSecurity(
        user = "op",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9403")])
    fun `open shipment, mint container, add mixed-order lines, close flips PACKED, complete marks members, cancel restores`() {
        val g = readyGroup("pp")
        val ship = port.openGroupShipment(
            OpenGroupShipmentRequest(
                CLIENT, g.waveId, "W-x", g.groupId, "01",
                listOf(MemberOrderRef(g.orderIds[0], "O1"), MemberOrderRef(g.orderIds[1], "O2")),
            ),
        )
        assertEquals(ShipmentState.PACKING.code, ship.state)
        assertTrue(ship.shipmentNumber.startsWith("SHP-W-x-01"), "got ${ship.shipmentNumber}")
        assertEquals(ship.id, port.findOpenGroupShipment(g.groupId, CLIENT)!!.id)
        // Burndown-6 A8: the batched read answers identically for one group; empty in -> empty out.
        assertEquals(ship.id, port.findOpenGroupShipmentIds(listOf(g.groupId), CLIENT)[g.groupId])
        assertTrue(port.findOpenGroupShipmentIds(emptyList(), CLIENT).isEmpty())
        val again = port.openGroupShipment(
            OpenGroupShipmentRequest(
                CLIENT, g.waveId, "W-x", g.groupId, "01",
                listOf(MemberOrderRef(g.orderIds[0], "O1"), MemberOrderRef(g.orderIds[1], "O2")),
            ),
        )
        assertEquals(ship.id, again.id, "idempotent reopen returns the same shipment")

        val staging = seedShipStagingAndReturn()
        val c1 = port.openContainer(ship.id, OpenContainerRequest(null, "CARTON", staging.first, staging.second), CLIENT)
        assertEquals(ShippingUnit.STATE_OPEN, c1.state)
        assertEquals("${ship.shipmentNumber}-SU1", c1.shippingUnitNumber)

        // 25 units spanning both orders (20 from o1's slice, 5 from o2's) into one container.
        val c1b = port.addLines(
            ship.id, c1.id,
            listOf(
                PackLineAllocation(g.orderIds[0], g.lineIds[0], g.pickIds[0], g.cartStockUnitIds[0], g.itemId, g.sku, null, BigDecimal(20)),
                PackLineAllocation(g.orderIds[1], g.lineIds[1], g.pickIds[1], g.cartStockUnitIds[1], g.itemId, g.sku, null, BigDecimal(5)),
            ),
            CLIENT,
        )
        assertEquals(2, c1b.lines.size)
        assertEquals(setOf(PICKED), stockStates(c1.unitLoadId).toSet(), "moved stock lands PICKED on the container")
        assertEquals(
            BigDecimal(25).setScale(4),
            port.packedByLines(g.lineIds, CLIENT).values.fold(BigDecimal.ZERO) { a, b -> a + b }.setScale(4),
        )

        assertThrows(FulfillmentException.InvalidPackRequest::class.java) {
            port.closeContainer(ship.id, c1.id, BigDecimal.ZERO, CLIENT)
        }
        val closed = port.closeContainer(ship.id, c1.id, BigDecimal("3.5"), CLIENT)
        assertEquals(ShipmentState.PACKED.code, closed.state)
        assertEquals(setOf(PACKED), stockStates(c1.unitLoadId).toSet(), "close flips PICKED -> PACKED")

        // Complete is refused while 10 units remain unpacked: the port only checks that every
        // container is closed, so open and fill a second container first.
        val c2 = port.openContainer(ship.id, OpenContainerRequest(null, "CARTON", staging.first, staging.second), CLIENT)
        port.addLines(
            ship.id, c2.id,
            listOf(
                PackLineAllocation(g.orderIds[1], g.lineIds[1], g.pickIds[1], g.cartStockUnitIds[1], g.itemId, g.sku, null, BigDecimal(10)),
            ),
            CLIENT,
        )
        assertThrows(FulfillmentException.InvalidPackRequest::class.java) {
            port.completeGroupShipment(ship.id, CLIENT, false)
        }
        port.closeContainer(ship.id, c2.id, BigDecimal("1.0"), CLIENT)
        val done = port.completeGroupShipment(ship.id, CLIENT, sendToShipping = false)
        assertEquals(ShipmentState.PACKED.code, done.state)
        g.orderIds.forEach { assertEquals(PACKED, orderState(it), "members PACKED") }

        // packedByPicks: the per-slice consumption ledger over both containers.
        val byPick = port.packedByPicks(ship.id, CLIENT)
        assertEquals(BigDecimal(20).setScale(4), byPick.getValue(g.pickIds[0]).setScale(4))
        assertEquals(BigDecimal(15).setScale(4), byPick.getValue(g.pickIds[1]).setScale(4))

        // Reopen c2 (pre-manifest): PACKED -> OPEN, its stock PACKED -> PICKED, shipment back to
        // PACKING. This also leaves the two containers in DIFFERENT states for the cancel below,
        // so the restore exercises both of its branches -- c1 closed (needs the unpack first),
        // c2 already open (move-back only).
        val reopened = port.reopenContainer(ship.id, c2.id, CLIENT)
        assertEquals(ShippingUnit.STATE_OPEN, reopened.state)
        assertEquals(setOf(PICKED), stockStates(c2.unitLoadId).toSet(), "reopen flips PACKED -> PICKED")
        assertEquals(ShipmentState.PACKING.code, port.findOpenGroupShipment(g.groupId, CLIENT)!!.state)

        cancelAndAssertRestore(ship.id, c1.unitLoadId, g)
        repackAfterCancel(ship.id, g)
    }

    /**
     * Cancel before manifest: every container is emptied, all 35 units are back on the cart as
     * PICKED, the packed ledger reads zero again and the consolidation group is free. Extracted
     * out of the test method purely to keep it inside detekt's `LongMethod` ceiling.
     */
    private fun cancelAndAssertRestore(shipmentId: Long, containerUl: Long, g: ReadyGroup) {
        shippingLifecycleService.cancel(shipmentId)
        assertTrue(
            stockStates(containerUl).isEmpty() || stockStates(containerUl).all { it >= DELETABLE },
            "container emptied, got ${stockStates(containerUl)}",
        )
        assertEquals(BigDecimal(35).setScale(4), stockAmountOn(g.cartUnitLoadId).setScale(4))
        assertTrue(
            stockRows(g.cartUnitLoadId).all {
                (it["state"] as Number).toInt() == PICKED || (it["amount"] as Number).toDouble() == 0.0
            },
            "restored cart stock is PICKED again, got ${stockRows(g.cartUnitLoadId)}",
        )
        assertTrue(port.packedByLines(g.lineIds, CLIENT).values.all { it.signum() == 0 })
        assertNull(port.findOpenGroupShipment(g.groupId, CLIENT))
        assertTrue(port.findOpenGroupShipmentIds(listOf(g.groupId), CLIENT).isEmpty(), "cancel empties the batched read too")
    }

    /**
     * Burndown-6 A2 (WORKLIST row :2066), the PROOF, not a fix. The review row claimed group
     * cancel is defective because it "does not walk member orders back from PACKED". The premise
     * is invalid: order states are forward-only, the discrete path leaves the DeliveryOrder at
     * PACKED after cancel too, and `DefaultOrderProgressionPort.progressIfBehind` makes the
     * re-pack's `markPacked` a no-op on an order that is already there. This helper packs the
     * SAME group a second time, end to end, after the cancel above -- if the standing PACKED
     * state actually blocked anything, this is where it would surface.
     *
     * This test is therefore expected GREEN on its first run. A RED here would not be a bug to
     * fix in the test: it would mean the adjudication's premise is wrong and the row is a real
     * defect after all.
     */
    private fun repackAfterCancel(canceledShipmentId: Long, g: ReadyGroup) {
        val reship = port.openGroupShipment(
            OpenGroupShipmentRequest(
                CLIENT, g.waveId, "W-x", g.groupId, "02",
                listOf(MemberOrderRef(g.orderIds[0], "O1"), MemberOrderRef(g.orderIds[1], "O2")),
            ),
        )
        assertTrue(reship.id != canceledShipmentId, "a canceled group shipment never gets reopened")
        assertEquals(reship.id, port.findOpenGroupShipment(g.groupId, CLIENT)!!.id)
        assertEquals(PACKED, orderState(g.orderIds[0]), "cancel leaves the member order at PACKED (forward-only)")

        val staging = seedShipStagingAndReturn()
        val c = port.openContainer(reship.id, OpenContainerRequest(null, "CARTON", staging.first, staging.second), CLIENT)
        port.addLines(
            reship.id, c.id,
            listOf(
                PackLineAllocation(g.orderIds[0], g.lineIds[0], g.pickIds[0], g.cartStockUnitIds[0], g.itemId, g.sku, null, BigDecimal(20)),
                PackLineAllocation(g.orderIds[1], g.lineIds[1], g.pickIds[1], g.cartStockUnitIds[1], g.itemId, g.sku, null, BigDecimal(15)),
            ),
            CLIENT,
        )
        port.closeContainer(reship.id, c.id, BigDecimal("4.5"), CLIENT)
        val done = port.completeGroupShipment(reship.id, CLIENT, sendToShipping = false)
        assertEquals(ShipmentState.PACKED.code, done.state, "the re-pack completes exactly like the first one")
        g.orderIds.forEach { assertEquals(PACKED, orderState(it), "members still PACKED after the re-pack") }
    }

    @Test
    @TestSecurity(
        user = "op",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9403")])
    fun `scanning an LPN - must exist, be empty, and not be a live container`() {
        val g = readyGroup("lpn")
        val ship = port.openGroupShipment(
            OpenGroupShipmentRequest(
                CLIENT, g.waveId, "W-y", g.groupId, "01",
                listOf(MemberOrderRef(g.orderIds[0], "O1"), MemberOrderRef(g.orderIds[1], "O2")),
            ),
        )
        val staging = seedShipStagingAndReturn()
        val emptyUl = createUnitLoad("LPN-EMPTY-${System.nanoTime()}")
        val c = port.openContainer(ship.id, OpenContainerRequest(emptyUl, "CARTON", staging.first, staging.second), CLIENT)
        assertEquals(emptyUl, c.unitLoadId)
        assertThrows(FulfillmentException.InvalidPackRequest::class.java) {
            port.openContainer(ship.id, OpenContainerRequest(emptyUl, "CARTON", staging.first, staging.second), CLIENT)
        }
        assertThrows(FulfillmentException.InvalidPackRequest::class.java) {
            port.openContainer(ship.id, OpenContainerRequest(g.cartUnitLoadId, "CARTON", staging.first, staging.second), CLIENT)
        }
        assertThrows(FulfillmentException.NotFound::class.java) {
            port.openContainer(ship.id, OpenContainerRequest(-5, "CARTON", staging.first, staging.second), CLIENT)
        }
    }

    @Test
    @TestSecurity(
        user = "op",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9403")])
    fun `a lot-bearing line packs under its own lot and the cancel restores that same lot`() {
        val g = readyGroup("lot", lot = LOT)
        assertEquals(LOT, g.lotNumber, "precondition: the batch pick slices must carry the seeded lot")
        val ship = port.openGroupShipment(
            OpenGroupShipmentRequest(
                CLIENT, g.waveId, "W-z", g.groupId, "01",
                listOf(MemberOrderRef(g.orderIds[0], "O1"), MemberOrderRef(g.orderIds[1], "O2")),
            ),
        )
        val staging = seedShipStagingAndReturn()
        val c = port.openContainer(ship.id, OpenContainerRequest(null, "CARTON", staging.first, staging.second), CLIENT)
        val filled = port.addLines(
            ship.id, c.id,
            listOf(
                PackLineAllocation(g.orderIds[0], g.lineIds[0], g.pickIds[0], g.cartStockUnitIds[0], g.itemId, g.sku, LOT, BigDecimal(20)),
                PackLineAllocation(g.orderIds[1], g.lineIds[1], g.pickIds[1], g.cartStockUnitIds[1], g.itemId, g.sku, LOT, BigDecimal(15)),
            ),
            CLIENT,
        )
        assertEquals(listOf(LOT, LOT), filled.lines.map { it.lotNumber }, "the lot rides the container lines")
        assertEquals(setOf<String?>(LOT), lotsOn(c.unitLoadId), "and the moved stock keeps it")
        port.closeContainer(ship.id, c.id, BigDecimal("2.0"), CLIENT)

        // The whole point: sourceStockFor's same-item AND same-lot branch is what picks the row to
        // move back, so the lot survives the round trip instead of being flattened to null.
        cancelAndAssertRestore(ship.id, c.unitLoadId, g)
        assertEquals(setOf<String?>(LOT), lotsOn(g.cartUnitLoadId), "restored cart stock keeps its lot")
    }

    /**
     * CRITICAL 1 (Sprint C final-review fix wave): a CONSOLIDATION container's line is the only
     * record of which cart its quantity came off, so deleting one would strand the goods on the
     * container and let the same pick slice be packed twice. The whole container (or the whole
     * shipment) is the only legal unit of undo here. The PACKOUT-origin counterpart -- a line
     * removal that DOES go through -- is `ShippingLifecycleServiceTest`'s "removeLine deletes only
     * that line" test; both origins are covered, in the suite that owns each shape's fixture.
     */
    @Test
    @TestSecurity(
        user = "op",
        roles = [
            "product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write",
            "layout-read", "layout-write", "fulfillment-read", "fulfillment-write", "MANAGER", "VIEWER", "OPERATOR",
        ],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9403")])
    fun `removeLine on a consolidation container is refused, and the container keeps its stock`() {
        val g = readyGroup("rml")
        val ship = port.openGroupShipment(
            OpenGroupShipmentRequest(
                CLIENT, g.waveId, "W-rml", g.groupId, "01",
                listOf(MemberOrderRef(g.orderIds[0], "O1"), MemberOrderRef(g.orderIds[1], "O2")),
            ),
        )
        val staging = seedShipStagingAndReturn()
        val c = port.openContainer(ship.id, OpenContainerRequest(null, "CARTON", staging.first, staging.second), CLIENT)
        val filled = port.addLines(
            ship.id, c.id,
            listOf(
                PackLineAllocation(g.orderIds[0], g.lineIds[0], g.pickIds[0], g.cartStockUnitIds[0], g.itemId, g.sku, null, BigDecimal(20)),
                PackLineAllocation(g.orderIds[1], g.lineIds[1], g.pickIds[1], g.cartStockUnitIds[1], g.itemId, g.sku, null, BigDecimal(15)),
            ),
            CLIENT,
        )
        val lineId = filled.lines.first().id

        val refused = assertThrows(FulfillmentException.ValidationFailed::class.java) {
            shippingLifecycleService.removeLine(ship.id, c.id, lineId)
        }
        assertTrue(
            refused.message!!.contains("consolidation container"),
            "message should name the container's origin, got '${refused.message}'",
        )
        assertEquals(
            BigDecimal(35).setScale(4),
            port.packedByLines(g.lineIds, CLIENT).values.fold(BigDecimal.ZERO) { a, b -> a + b }.setScale(4),
            "the ledger is untouched by the refusal",
        )
        assertEquals(2, port.findOpenGroupShipment(g.groupId, CLIENT)!!.containers.single().lines.size)
    }

    private companion object {
        const val CLIENT = 9403L
        const val LOT = "LOT-C1"
        const val PICKED = 600
        const val PACKED = 650
        const val DELETABLE = 1000
    }
}

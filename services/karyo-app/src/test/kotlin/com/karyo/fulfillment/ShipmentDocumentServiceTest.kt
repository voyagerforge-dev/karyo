package com.karyo.fulfillment

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.ShipmentDocumentService
import com.karyo.fulfillment.service.ShippingService
import com.karyo.security.TenantContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class ShipmentDocumentServiceTest {
    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var packingService: PackingService
    @Inject lateinit var shippingService: ShippingService
    @Inject lateinit var documentService: ShipmentDocumentService
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var entityManager: EntityManager
    @Inject lateinit var shipmentRepository: ShipmentRepository
    @Inject lateinit var shipmentOrderRepository: ShipmentOrderRepository
    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository

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
            .body("""{"itemDataId":$itemDataId,"itemDataNumber":"$number","amount":$amount,"unitLoadId":$ulId,"state":300}""")
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedPackStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-${System.nanoTime()}","usages":["PACK_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"PStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"PACK-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    private fun seedShipStaging() {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"SHIP-STG-${System.nanoTime()}","usages":["SHIP_STAGING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val ltId = given().contentType(ContentType.JSON).body("""{"name":"SStg-${System.nanoTime()}"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON)
            .body("""{"name":"SHIP-STG-LOC-${System.nanoTime()}","locationTypeId":$ltId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
    }

    /** Order created WITH a ship-to address (customerName + city + country) so documents render. */
    private fun seedAndReleaseOrderWithAddress(): Long {
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "ORD-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-$s")
        createStock(ul, pid, num, 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Acme","city":"Springfield","country":"US",""" +
                    """"lines":[{"itemDataId":$pid,"amount":60.0}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /**
     * Sprint C, Task 5: a released order with a distinguishable ship-to city + SKU (so a
     * group-shipment document's per-member attribution and address resolution are provable in
     * the same assertion). Returns `(orderId, itemDataId, sku)`.
     */
    private fun seedOrderWithAddress(tag: String, city: String): Triple<Long, Long, String> {
        val s = System.nanoTime()
        // Item-unit name is capped at 20 chars (CreateItemUnitRequest) -- a truncated suffix
        // keeps this well under the limit even with the tag prefix.
        val iu = createItemUnit("IU$tag${s % 1_000_000_000L}")
        val sku = "SKU-$tag-$s"
        val pid = createProduct(sku, iu)
        val ul = createUnitLoad("UL-$tag-$s")
        createStock(ul, pid, sku, 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Cust-$tag","city":"$city","country":"US",""" +
                    """"lines":[{"itemDataId":$pid,"amount":10.0}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return Triple(orderId, pid, sku)
    }

    /**
     * Sprint C, Task 5: persists a GROUP shipment (no [Shipment.deliveryOrderId], SHIPPING/670 so
     * every document -- slip/packet-list/BOL/label -- is available in one seed) plus its member
     * [ShipmentOrder] rows, bypassing REST/wave -- no wave/pack-out flow produces a group
     * shipment in this test's scope (see [GroupShipmentModelIT]'s proven direct-persistence
     * pattern this mirrors). `clientId` is a function PARAMETER, never an outer-class property --
     * the KDoc on [GroupShipmentModelIT.persistGroupShipment] documents why: an outer `clientId`
     * would be shadowed inside the `apply {}` block by [Shipment]'s own field of the same name.
     */
    @Transactional
    fun persistGroupShipment(clientId: Long, members: List<Pair<Long, String>>): Long {
        val shp = Shipment().apply {
            this.clientId = clientId
            shipmentNumber = "SHP-GRP-DOC-${System.nanoTime()}"
            // Unique per seeded shipment: fulfillment V613 allows only ONE live shipment per
            // consolidation group, and this suite's DB is shared across its test methods.
            consolidationGroupId = System.nanoTime()
            state = 670
            carrierName = "UPS"
            carrierService = "GROUND"
            trackingNumber = "TRK-${System.nanoTime()}"
        }
        shipmentRepository.persist(shp)
        members.forEach { (orderId, label) ->
            val so = ShipmentOrder().apply {
                this.clientId = clientId
                shipmentId = shp.id!!
                deliveryOrderId = orderId
                deliveryOrderNumber = label
            }
            shipmentOrderRepository.persist(so)
        }
        return shp.id!!
    }

    /** Sprint C, Task 5: persists one CONSOLIDATION (closed) container with the given lines --
     *  each line optionally attributed to a member order via [deliveryOrderId]. Returns
     *  `(unitId, shippingUnitNumber)` -- the container number is asserted on directly. */
    @Transactional
    fun persistUnitWithLines(
        clientId: Long,
        shipmentId: Long,
        positionIndex: Int,
        lines: List<Triple<Long?, Long, String>>,
    ): Pair<Long, String> {
        val u = ShippingUnit().apply {
            this.clientId = clientId
            this.shipmentId = shipmentId
            this.positionIndex = positionIndex
            shippingUnitNumber = "SU-$positionIndex-${System.nanoTime()}"
            type = "CARTON"
            weight = BigDecimal("1.0")
            state = 650
            origin = ShippingUnit.ORIGIN_CONSOLIDATION
        }
        shippingUnitRepository.persist(u)
        lines.forEach { (orderId, itemDataId, sku) ->
            val line = ShippingUnitLine().apply {
                this.clientId = clientId
                shippingUnitId = u.id!!
                this.itemDataId = itemDataId
                itemDataNumber = sku
                amount = BigDecimal("5")
                deliveryOrderId = orderId
            }
            shippingUnitRepository.persistLine(line)
        }
        return u.id!! to u.shippingUnitNumber
    }

    private fun pdfText(bytes: ByteArray): String = PDDocument.load(bytes).use { PDFTextStripper().getText(it) }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `slip renders at PACKED, BOL gated until SHIPPING, all render after manifest`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        tenantContext.clientId = 1L
        val po = pickOrderService.releaseToPicking(orderId).single()
        pickOrderService.picksOf(po.id!!).forEach { pickOrderService.confirmPick(it.id!!, it.plannedAmount, null) }
        entityManager.clear()
        val shipment = packingService.openPacking(orderId)
        packingService.pack(shipment.id!!, BigDecimal("2.5"), "CARTON")
        entityManager.clear()

        // PACKED(650): slip OK, BOL gated (needs SHIPPING 670)
        assertThat(String(documentService.packingSlipPdf(shipment.id!!).copyOfRange(0, 4))).isEqualTo("%PDF")
        assertThatThrownBy { documentService.bolPdf(shipment.id!!) }
            .isInstanceOf(FulfillmentException.DocumentNotReady::class.java)

        // manifest -> SHIPPING(670): BOL + label render
        shippingService.manifest(shipment.id!!, "UPS", "GROUND", null)
        entityManager.clear()
        assertThat(String(documentService.bolPdf(shipment.id!!).copyOfRange(0, 4))).isEqualTo("%PDF")
        val unitId = packingService.unitsOf(shipment.id!!).first().id!!
        assertThat(documentService.labelZpl(unitId)).contains("^XA")
    }

    // ── Sprint C, Task 5: shipping documents for GROUP shipments ─────────────────────

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `BOL lists both member orders and SKUs, packet list shows mixed-container attribution`() {
        val (orderA, _, skuA) = seedOrderWithAddress("A", "Springfield")
        val (orderB, _, skuB) = seedOrderWithAddress("B", "Metropolis")
        tenantContext.clientId = 1L
        val shipmentId = persistGroupShipment(1L, listOf(orderA to "A", orderB to "B"))
        // SU1: order A only. SU2: mixed A + B -- the "container with both orders".
        val (_, su1Number) = persistUnitWithLines(1L, shipmentId, 1, listOf(Triple(orderA, 1L, skuA)))
        val (_, su2Number) = persistUnitWithLines(
            1L, shipmentId, 2,
            listOf(Triple(orderA, 1L, skuA), Triple(orderB, 2L, skuB)),
        )
        entityManager.clear()

        val bolBytes = documentService.bolPdf(shipmentId)
        assertThat(String(bolBytes.copyOfRange(0, 4))).isEqualTo("%PDF")
        val bolText = pdfText(bolBytes)
        assertThat(bolText).contains("Orders: A, B")
        assertThat(bolText).contains(skuA)
        assertThat(bolText).contains(skuB)

        // Row-specific: the document header ALSO renders "A, B" (Order: {combinedOrderNumber}),
        // so a whole-text `contains("A, B")` would pass even if packetListUnitRow's per-unit
        // "orders" column were broken. Anchor on each container's own shipping-unit number and
        // slice out just that row's text (the table has no rows after SU2's) to prove the column
        // itself, not the header line above the table.
        val packetListText = pdfText(documentService.packetListPdf(shipmentId))
        val su1Row = packetListText.substringAfter(su1Number).substringBefore(su2Number)
        val su2Row = packetListText.substringAfter(su2Number)
        assertThat(su1Row).contains("A")
        assertThat(su1Row).doesNotContain("A, B")
        assertThat(su2Row).contains("A, B")
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packing slip filters to one member order, or sections every member when unfiltered`() {
        val (orderA, _, skuA) = seedOrderWithAddress("A", "Springfield")
        val (orderB, _, skuB) = seedOrderWithAddress("B", "Metropolis")
        tenantContext.clientId = 1L
        val shipmentId = persistGroupShipment(1L, listOf(orderA to "A", orderB to "B"))
        val (_, su1Number) = persistUnitWithLines(1L, shipmentId, 1, listOf(Triple(orderA, 1L, skuA)))
        val (_, su2Number) = persistUnitWithLines(
            1L, shipmentId, 2,
            listOf(Triple(orderA, 1L, skuA), Triple(orderB, 2L, skuB)),
        )
        entityManager.clear()

        // orderId = B: only B's line, only the container that carries it (SU2), never SU1.
        val filteredText = pdfText(documentService.packingSlipPdf(shipmentId, orderId = orderB))
        assertThat(filteredText).contains("Order B")
        assertThat(filteredText).contains(su2Number)
        assertThat(filteredText).contains(skuB)
        assertThat(filteredText).doesNotContain(su1Number)
        assertThat(filteredText).doesNotContain(skuA)

        // no orderId: one section per member.
        val unfilteredText = pdfText(documentService.packingSlipPdf(shipmentId))
        assertThat(unfilteredText).contains("Order A")
        assertThat(unfilteredText).contains("Order B")
        assertThat(unfilteredText).contains(skuA)
        assertThat(unfilteredText).contains(skuB)

        // an orderId that isn't a member of this shipment is refused, not silently ignored.
        assertThatThrownBy { documentService.packingSlipPdf(shipmentId, orderId = 987654321L) }
            .isInstanceOf(FulfillmentException.NotFound::class.java)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label defaults to the first member order's ship-to when no override is set`() {
        val (orderA, _, skuA) = seedOrderWithAddress("A", "Springfield")
        val (orderB, _, skuB) = seedOrderWithAddress("B", "Metropolis")
        tenantContext.clientId = 1L
        val shipmentId = persistGroupShipment(1L, listOf(orderA to "A", orderB to "B"))
        val (su1Id, _) = persistUnitWithLines(1L, shipmentId, 1, listOf(Triple(orderA, 1L, skuA)))
        persistUnitWithLines(1L, shipmentId, 2, listOf(Triple(orderB, 2L, skuB)))
        entityManager.clear()

        val zpl = documentService.labelZpl(su1Id)
        assertThat(zpl).contains("Springfield")
        assertThat(zpl).doesNotContain("Metropolis")
    }
}

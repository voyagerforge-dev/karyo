package com.karyo.fulfillment

import com.karyo.docstore.repository.StoredDocumentRepository
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.math.BigDecimal
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test

@QuarkusTest
class ShipmentDocumentRestTest {

    @Inject lateinit var storedDocumentRepository: StoredDocumentRepository
    @Inject lateinit var shipmentRepository: ShipmentRepository
    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository

    /** Sets the P2 flat ship-to override on a persisted unit, bypassing REST (no PATCH endpoint
     *  exists yet -- Task 2 only adds the fields + the label read-side). */
    @Transactional
    fun setShipToOverride(
        unitId: Long,
        name: String,
        street: String,
        streetNumber: String,
        zip: String,
        city: String,
        country: String,
    ) {
        val unit = shippingUnitRepository.findById(unitId) ?: error("shipping unit $unitId not found")
        unit.shipToName = name
        unit.shipToStreet = street
        unit.shipToStreetNumber = streetNumber
        unit.shipToZip = zip
        unit.shipToCity = city
        unit.shipToCountry = country
    }

    /**
     * Task 3 (outbound-completion sprint): seeds a PACKED shipment with TWO shipping units (each
     * one line), bypassing REST -- no multi-packet PackoutStrategy ships yet (ONE_TO_ONE always
     * produces exactly one unit), so a direct-repository seed is the only way to pin the packing
     * slip's per-unit grouping today.
     */
    @Transactional
    fun seedTwoUnitShipment(orderId: Long): Long {
        val s = Shipment().apply {
            clientId = 1L
            shipmentNumber = "SHIP-GROUP-${System.nanoTime()}"
            deliveryOrderId = orderId
            deliveryOrderNumber = "DO-$orderId"
            state = 650
        }
        shipmentRepository.persist(s)
        listOf(1 to "SKU-BOX-A", 2 to "SKU-BOX-B").forEach { (idx, sku) ->
            val u = ShippingUnit().apply {
                clientId = 1L
                shipmentId = s.id!!
                positionIndex = idx
                shippingUnitNumber = "${s.shipmentNumber}-SU$idx"
                type = "CARTON"
                weight = BigDecimal("1.0")
                state = 650
            }
            shippingUnitRepository.persist(u)
            val line = ShippingUnitLine().apply {
                clientId = 1L
                shippingUnitId = u.id!!
                itemDataId = idx.toLong()
                itemDataNumber = sku
                amount = BigDecimal("5")
            }
            shippingUnitRepository.persistLine(line)
        }
        return s.id!!
    }

    /** Seeds a bare shipment owned by [owner], bypassing REST so a foreign owner can be created. */
    @Transactional
    fun seedForeignShipment(owner: Long): Long {
        val s = Shipment().apply {
            clientId = owner
            shipmentNumber = "SHIP-FOREIGN-${System.nanoTime()}"
            deliveryOrderId = 1
            deliveryOrderNumber = "DO-FOREIGN"
            state = 650
        }
        shipmentRepository.persist(s)
        return s.id!!
    }

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

    /** Order created WITH a ship-to address so documents render. */
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

    private fun releaseToPickingAndPickAll(orderId: Long) {
        val po = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath()
        po.getList<Map<String, Any>>("[0].picks").forEach { pick ->
            val amt = (pick["plannedAmount"] as Number).toDouble()
            given().contentType(ContentType.JSON).body("""{"pickedAmount":$amt}""")
                .`when`().post("/api/v1/picks/${pick["id"]}/confirm").then().statusCode(200)
        }
    }

    /** release -> pick -> pack; returns the shipmentId at PACKED(650). */
    private fun packShipment(orderId: Long): Long {
        val shipmentId = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/shipments").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON).body("""{"weight":2.5,"type":"CARTON"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/pack").then().statusCode(200)
        return shipmentId
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `all three documents render after manifest`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)
        given().contentType(ContentType.JSON).body("""{"carrierName":"UPS","carrierService":"GROUND"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/manifest").then().statusCode(200)

        val unitId = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getLong("shippingUnits[0].id")

        given().`when`().get("/api/v1/shipments/$shipmentId/packing-slip.pdf")
            .then().statusCode(200).contentType("application/pdf")
        given().`when`().get("/api/v1/shipments/$shipmentId/bol.pdf")
            .then().statusCode(200).contentType("application/pdf")
        given().`when`().get("/api/v1/shipping-units/$unitId/label.zpl")
            .then().statusCode(200).contentType(startsWith("text/plain"))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `BOL is gated before manifest but packing-slip renders at PACKED`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId) // PACKED(650), no manifest

        given().`when`().get("/api/v1/shipments/$shipmentId/packing-slip.pdf")
            .then().statusCode(200).contentType("application/pdf")
        given().`when`().get("/api/v1/shipments/$shipmentId/bol.pdf")
            .then().statusCode(409)
    }

    // ── Task 3: packing slip groups lines per shipping unit ──────────────

    @Test
    @TestSecurity(user = "ff", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packing slip on a 2-unit shipment renders two per-box groups`() {
        val shipmentId = seedTwoUnitShipment(orderId = 999L)

        val pdf = given().`when`().get("/api/v1/shipments/$shipmentId/packing-slip.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray()

        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        assertThat(text).contains("Box 1:")
        assertThat(text).contains("Box 2:")
        assertThat(text).contains("SKU-BOX-A")
        assertThat(text).contains("SKU-BOX-B")
    }

    // ── P2: label ship-to override + Box i of N (outbound-completion sprint, Task 2) ────────

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label renders the order address by default and Box 1 of 1`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)
        given().contentType(ContentType.JSON).body("""{"carrierName":"UPS","carrierService":"GROUND"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/manifest").then().statusCode(200)

        val unitId = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getLong("shippingUnits[0].id")

        val zpl = given().`when`().get("/api/v1/shipping-units/$unitId/label.zpl")
            .then().statusCode(200).extract().asString()
        assertThat(zpl).contains("Acme")
        assertThat(zpl).contains("Springfield")
        assertThat(zpl).contains("Box 1 of 1")
    }

    /**
     * CRITICAL 1 second half (final-review fix wave, outbound-completion sprint): "Box i of N"
     * must stay coherent with `positionIndex` after a `removeUnit` hard-delete, not silently
     * shrink back to a live-unit COUNT. Sequence: pack (unit #1) -> ad-hoc attach (unit #2) ->
     * removeUnit(#1) (regresses to PACKING, one live unit survives at positionIndex 2) -> pack
     * again (numbering fix continues from the MAX positionIndex seen, 2, so the fresh unit lands
     * on #3) -> manifest -> label. N must read 3 (the highest positionIndex ever issued), not 2
     * (the live-unit count), or the label would misrepresent the box count against what was
     * physically ever produced for this shipment.
     */
    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label's Box i of N stays coherent with the max positionIndex after a unit removal`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId) // unit #1, positionIndex 1

        val s = System.nanoTime()
        val iu = createItemUnit("ADH-${s.toString().takeLast(10)}"); val num = "ADH-SKU-$s"
        val pid = createProduct(num, iu); val ulId = createUnitLoad("ADH-UL-$s")
        createStock(ulId, pid, num, 10.0)
        given().contentType(ContentType.JSON).body("""{"unitLoadId":$ulId}""")
            .`when`().post("/api/v1/shipments/$shipmentId/shipping-units").then().statusCode(201)
        // unit #2 (ad-hoc), positionIndex 2

        val unitsAfterAdHoc = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("shippingUnits")
        val firstUnitId = unitsAfterAdHoc.single { it["positionIndex"] == 1 }["id"]
        given().`when`().delete("/api/v1/shipments/$shipmentId/shipping-units/$firstUnitId")
            .then().statusCode(200)
        // shipment regressed to PACKING, ad-hoc unit (positionIndex 2) is the sole survivor

        given().contentType(ContentType.JSON).body("""{"weight":2.5,"type":"CARTON"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/pack").then().statusCode(200)
        // fresh packout unit lands on positionIndex 3 (max-based numbering), shipment back at PACKED

        given().contentType(ContentType.JSON).body("""{"carrierName":"UPS","carrierService":"GROUND"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/manifest").then().statusCode(200)

        val units = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("shippingUnits")
        assertThat(units).hasSize(2)
        assertThat(units.map { it["positionIndex"] }).containsExactlyInAnyOrder(2, 3)

        val unitAtThree = units.single { it["positionIndex"] == 3 }
        val zplThree = given().`when`().get("/api/v1/shipping-units/${unitAtThree["id"]}/label.zpl")
            .then().statusCode(200).extract().asString()
        assertThat(zplThree).contains("Box 3 of 3")

        val unitAtTwo = units.single { it["positionIndex"] == 2 }
        val zplTwo = given().`when`().get("/api/v1/shipping-units/${unitAtTwo["id"]}/label.zpl")
            .then().statusCode(200).extract().asString()
        assertThat(zplTwo).contains("Box 2 of 3")
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `label renders the unit's ship-to override instead of the order address when set`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)
        given().contentType(ContentType.JSON).body("""{"carrierName":"UPS","carrierService":"GROUND"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/manifest").then().statusCode(200)

        val unitId = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getLong("shippingUnits[0].id")
        setShipToOverride(unitId, "Override Co", "Elm St", "5", "99999", "Overrideville", "US")

        val zpl = given().`when`().get("/api/v1/shipping-units/$unitId/label.zpl")
            .then().statusCode(200).extract().asString()
        assertThat(zpl).contains("Override Co")
        assertThat(zpl).contains("Overrideville")
        assertThat(zpl).doesNotContain("Acme")
        assertThat(zpl).doesNotContain("Springfield")
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `documents are forbidden without fulfillment-read`() {
        given().`when`().get("/api/v1/shipments/1/bol.pdf").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `bol for a non-existent shipment returns 404`() {
        given().`when`().get("/api/v1/shipments/99999999/bol.pdf").then().statusCode(404)
    }

    // ── D8: packet content list ──────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet content list renders after packing`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)

        val unitId = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getLong("shippingUnits[0].id")

        given().`when`().get("/api/v1/shipping-units/$unitId/content-list.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray().let {
                assertThat(String(it.copyOfRange(0, 4))).isEqualTo("%PDF")
            }
    }

    @Test
    @TestSecurity(user = "ff", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet content list for a non-existent shipping unit returns 404`() {
        given().`when`().get("/api/v1/shipping-units/99999999/content-list.pdf").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet content list is forbidden without fulfillment-read`() {
        given().`when`().get("/api/v1/shipping-units/1/content-list.pdf").then().statusCode(403)
    }

    // ── D9: shipment-level packet list ───────────────────────────────────
    // Scope adjudication (recorded per the docs-labels sprint brief): D9's original
    // "picking/shipping/delivery lists" wording collapses to ONE shipment-level packet list
    // here — the picking list is D10's ticket (pick-ticket.pdf, already shipped), and the
    // delivery grouping is D7's note (delivery-note.pdf, already shipped). This endpoint is
    // the shipping-side list: one row per ShippingUnit on the shipment.

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet list renders after packing with unit number and total qty per row`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)

        val unitNumber = given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getString("shippingUnits[0].shippingUnitNumber")

        val pdf = given().`when`().get("/api/v1/shipments/$shipmentId/packet-list.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray()
        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")

        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        assertThat(text).contains(unitNumber)
        // The order was released with a single 60.0 line, fully picked into one carton —
        // total qty for the row must be the picked amount.
        assertThat(text).contains("60")
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet list is gated before PACKED`() {
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        // Shipment created but NOT packed yet -- still PACKING(640), below the PACKET_LIST
        // gate (PACKED/650, same as packing-slip).
        val shipmentId = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/shipments").then().statusCode(201).extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/shipments/$shipmentId/packet-list.pdf").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "ff", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet list for a non-existent shipment returns 404`() {
        given().`when`().get("/api/v1/shipments/99999999/packet-list.pdf").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet list is forbidden without fulfillment-read`() {
        given().`when`().get("/api/v1/shipments/1/packet-list.pdf").then().statusCode(403)
    }

    // ── Task 2: ?store=true opt-in archiving (docstore-templates sprint) ────────────────────

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `bol with store=true archives the PDF under the shipment's client`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)
        given().contentType(ContentType.JSON).body("""{"carrierName":"UPS","carrierService":"GROUND"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/manifest").then().statusCode(200)

        given().`when`().get("/api/v1/shipments/$shipmentId/bol.pdf?store=true")
            .then().statusCode(200).contentType("application/pdf")

        val row = storedDocumentRepository
            .find("entityType = ?1 and entityId = ?2 and documentType = ?3", "shipment", shipmentId, "bol")
            .firstResult()
        assertThat(row).isNotNull
        assertThat(row!!.clientId).isEqualTo(1L)
        assertThat(row.fileName).isEqualTo("bol-$shipmentId.pdf")
        assertThat(row.mediaType).isEqualTo("application/pdf")
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `bol without store defaults to not archiving`() {
        seedPackStaging(); seedShipStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val shipmentId = packShipment(orderId)
        given().contentType(ContentType.JSON).body("""{"carrierName":"UPS","carrierService":"GROUND"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/manifest").then().statusCode(200)

        given().`when`().get("/api/v1/shipments/$shipmentId/bol.pdf")
            .then().statusCode(200).contentType("application/pdf")

        val count = storedDocumentRepository
            .count("entityType = ?1 and entityId = ?2 and documentType = ?3", "shipment", shipmentId, "bol")
        assertThat(count).isZero()
    }

    @Test
    @TestSecurity(user = "owner2", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "2"), Claim(key = "tenant_code", value = "OTHER")])
    fun `a cross-tenant bol store=true request 404s and stores nothing`() {
        val shipmentId = seedForeignShipment(owner = 1L)

        given().`when`().get("/api/v1/shipments/$shipmentId/bol.pdf?store=true").then().statusCode(404)

        val count = storedDocumentRepository
            .count("entityType = ?1 and entityId = ?2 and documentType = ?3", "shipment", shipmentId, "bol")
        assertThat(count).isZero()
    }
}

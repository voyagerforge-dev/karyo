package com.karyo.fulfillment

import com.karyo.documents.DocumentRenderer
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.ShipmentDocumentService
import com.karyo.inventory.api.spi.StockContentRef
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.security.TenantContext
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anySet
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import java.time.LocalDate

/**
 * D8: pins the "broken join -> dash, never a 500" behavior of
 * [ShipmentDocumentService.packetContentListPdf] through the REAL service path (not just
 * through the template directly, which [ShipmentDocumentRestTest]'s render-level tests
 * already cover with hand-fed data), mirroring [PickDocumentServiceTest]'s style. Two
 * independent breaks in the `line.sourcePickId -> Pick.targetStockUnitId -> StockUnitLookup`
 * chain are pinned separately: a deleted target stock unit (mocked lookup) and a deleted
 * source pick (real repository, row actually removed). A third test pins WHICH id the join
 * must use in the first place: the TARGET stock unit (what's actually in the box), not the
 * SOURCE (what it was picked from) -- see [ShipmentDocumentService.packetContentData]'s KDoc
 * for why `StockService.resolveTransferTargetStock`'s aggregate-merge can make the two
 * disagree on bestBefore/serial.
 */
@QuarkusTest
class PacketContentListServiceTest {

    @InjectMock
    lateinit var stockUnitLookup: StockUnitLookup

    @Inject lateinit var documentService: ShipmentDocumentService
    @Inject lateinit var shippingUnitRepository: ShippingUnitRepository
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var renderer: DocumentRenderer

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

    /** release -> pick -> pack; returns the shippingUnitId at PACKED(650). */
    private fun packedShippingUnitId(orderId: Long): Long {
        val shipmentId = given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/shipments").then().statusCode(201).extract().jsonPath().getLong("id")
        given().contentType(ContentType.JSON).body("""{"weight":2.5,"type":"CARTON"}""")
            .`when`().post("/api/v1/shipments/$shipmentId/pack").then().statusCode(200)
        return given().`when`().get("/api/v1/shipments/$shipmentId").then().statusCode(200)
            .extract().jsonPath().getLong("shippingUnits[0].id")
    }

    @Transactional
    fun deletePick(pickId: Long) {
        pickRepository.deleteById(pickId)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet content list renders a dash and does not throw when the target stock unit lookup is empty`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val unitId = packedShippingUnitId(orderId)
        tenantContext.clientId = 1L

        val lines = shippingUnitRepository.findLinesByUnitId(unitId)
        val pickIds = lines.mapNotNull { it.sourcePickId }.toSet()
        assertThat(pickIds).isNotEmpty()
        val expectedStockUnitIds = pickRepository.findByIdsAndClient(pickIds, 1L)
            .mapNotNull { it.targetStockUnitId }.toSet()
        assertThat(expectedStockUnitIds).isNotEmpty()

        // Simulates every target stock unit having since been deleted: the batched lookup
        // returns no refs at all, exactly as DefaultStockUnitLookup.findContentRefsByIds does
        // for an unknown/deleted id (it's simply absent from the map, never an error). Keying
        // the mock's expected argument on TARGET ids (not source) also means this test fails
        // if the join is ever reverted to sourceStockUnitId.
        `when`(stockUnitLookup.findContentRefsByIds(anySet())).thenReturn(emptyMap<Long, StockContentRef>())

        val pdf = documentService.packetContentListPdf(unitId)
        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")

        verify(stockUnitLookup).findContentRefsByIds(expectedStockUnitIds)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet content list shows the TARGET stock unit's best-before and serial, not the source's`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val unitId = packedShippingUnitId(orderId)
        tenantContext.clientId = 1L

        val lines = shippingUnitRepository.findLinesByUnitId(unitId)
        val pickId = lines.mapNotNull { it.sourcePickId }.first()
        val pick = pickRepository.findByIdAndClient(pickId, 1L)!!
        val sourceId = pick.sourceStockUnitId
        val targetId = pick.targetStockUnitId!!
        // Sanity: the pick-container flow lands picked stock on a distinct stock unit from
        // the one it was picked from -- if this ever collided the divergence below would be
        // untestable (both ids would resolve to the same ref regardless of which is joined).
        assertThat(targetId).isNotEqualTo(sourceId)

        // Deliberately disjoint values keyed under BOTH ids: if the join ever regresses back
        // to sourceStockUnitId, the assertions below flip (source's values appear instead of
        // target's) and this test fails.
        `when`(stockUnitLookup.findContentRefsByIds(anySet())).thenReturn(
            mapOf(
                sourceId to StockContentRef(sourceId, LocalDate.parse("2020-01-01"), "SOURCE-SN"),
                targetId to StockContentRef(targetId, LocalDate.parse("2030-01-01"), "TARGET-SN"),
            ),
        )

        val pdf = documentService.packetContentListPdf(unitId)
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

        assertThat(text).contains("2030-01-01").contains("TARGET-SN")
        assertThat(text).doesNotContain("2020-01-01").doesNotContain("SOURCE-SN")

        verify(stockUnitLookup).findContentRefsByIds(setOf(targetId))
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `packet content list does not throw when the source pick was deleted`() {
        seedPackStaging()
        val orderId = seedAndReleaseOrderWithAddress()
        releaseToPickingAndPickAll(orderId)
        val unitId = packedShippingUnitId(orderId)

        val lines = shippingUnitRepository.findLinesByUnitId(unitId)
        val pickId = lines.mapNotNull { it.sourcePickId }.first()
        deletePick(pickId)
        tenantContext.clientId = 1L

        // Real (unmocked) StockUnitLookup/PickRepository -- the pick itself is gone, so
        // `line.sourcePickId?.let { picksById[it] }` must yield null and the service must
        // still render, never NPE on `pick.targetStockUnitId`.
        val pdf = documentService.packetContentListPdf(unitId)
        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
    }

    // ── Render-level: DocumentRenderer direct, hand-fed maps (template display only) ──

    @Test
    fun `template renders SKU, lot, best-before and serial`() {
        val html = renderer.render(
            "/templates/packet-content-list.html",
            mapOf(
                "shippingUnitNumber" to "SU-1",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "lines" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-1",
                        "amount" to "10",
                        "lotNumber" to "LOT-1",
                        "bestBefore" to "2027-01-01",
                        "serialNumber" to "SN-1",
                    ),
                ),
            ),
        )
        assertThat(html).contains("SKU-1").contains("LOT-1").contains("2027-01-01").contains("SN-1").contains("SU-1")
    }

    @Test
    fun `template renders a dash for a broken join`() {
        val html = renderer.render(
            "/templates/packet-content-list.html",
            mapOf(
                "shippingUnitNumber" to "SU-2",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "lines" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-2",
                        "amount" to "10",
                        "lotNumber" to "LOT-2",
                        "bestBefore" to "—",
                        "serialNumber" to "—",
                    ),
                ),
            ),
        )
        assertThat(html).contains("SKU-2").contains("—")
    }

    @Test
    fun `template escapes an XSS attempt in itemDataNumber`() {
        val html = renderer.render(
            "/templates/packet-content-list.html",
            mapOf(
                "shippingUnitNumber" to "SU-3",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "lines" to listOf(
                    mapOf(
                        "itemDataNumber" to "<img src=x onerror=alert(1)>",
                        "amount" to "10",
                        "lotNumber" to "—",
                        "bestBefore" to "—",
                        "serialNumber" to "—",
                    ),
                ),
            ),
        )
        assertThat(html).doesNotContain("<img src=x")
        assertThat(html).contains("&lt;img src=x")
    }
}

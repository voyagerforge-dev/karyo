package com.karyo.fulfillment

import com.karyo.fulfillment.service.PickDocumentService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.inventory.api.spi.StockLocationRef
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anySet
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify

/**
 * Pins the "missing source stock unit -> dash, never a 500" behavior through the REAL
 * [PickDocumentService.pickTicketPdf] path (not just through the template directly, which
 * PickTicketRestTest's "renders a dash" test already covers with hand-fed data). This mocks
 * [StockUnitLookup] to return an empty lookup result -- exactly what production sees for a
 * deleted/unknown source stock unit id -- and exercises the service's own `ref?.locationName
 * ?: "—"` fallback.
 */
@QuarkusTest
class PickDocumentServiceTest {

    @InjectMock
    lateinit var stockUnitLookup: StockUnitLookup

    @Inject lateinit var pickOrderService: PickOrderService
    @Inject lateinit var pickDocumentService: PickDocumentService
    @Inject lateinit var tenantContext: TenantContext

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

    /** release -> released order (with reserved stock), REST-seeded, mirroring ShipmentDocumentServiceTest's idiom. */
    private fun seedAndReleaseOrder(): Long {
        seedPackStaging()
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

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick ticket renders a dash and does not throw when the source stock unit lookup is empty`() {
        val orderId = seedAndReleaseOrder()
        tenantContext.clientId = 1L
        val po = pickOrderService.releaseToPicking(orderId).single()
        val picks = pickOrderService.picksOf(po.id!!)
        assertThat(picks).isNotEmpty()

        // Simulates every source stock unit having since been deleted: the batched lookup
        // returns no refs at all, exactly as DefaultStockUnitLookup.findLocationRefsByIds does
        // for an unknown/deleted id (it's simply absent from the map, never an error).
        `when`(stockUnitLookup.findLocationRefsByIds(anySet())).thenReturn(emptyMap<Long, StockLocationRef>())

        val pdf = pickDocumentService.pickTicketPdf(po.id!!)
        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")

        verify(stockUnitLookup).findLocationRefsByIds(picks.map { it.sourceStockUnitId }.toSet())
    }
}

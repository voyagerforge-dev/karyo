package com.karyo.fulfillment

import com.karyo.docstore.repository.StoredDocumentRepository
import com.karyo.documents.DocumentRenderer
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@QuarkusTest
class PickTicketRestTest {

    @Inject lateinit var renderer: DocumentRenderer
    @Inject lateinit var storedDocumentRepository: StoredDocumentRepository

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

    /** release -> pick order created (not confirmed); returns pickOrderId. */
    private fun releaseToPicking(orderId: Long): Long =
        given().contentType(ContentType.JSON).body("""{"deliveryOrderId":$orderId}""")
            .`when`().post("/api/v1/pick-orders").then().statusCode(201).extract().jsonPath().getLong("[0].id")

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick ticket renders for a released pick order`() {
        val orderId = seedAndReleaseOrder()
        val poId = releaseToPicking(orderId)

        given().`when`().get("/api/v1/pick-orders/$poId/pick-ticket.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray().let {
                assertThat(String(it.copyOfRange(0, 4))).isEqualTo("%PDF")
            }
    }

    @Test
    @TestSecurity(user = "ff", roles = ["fulfillment-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick ticket for a non-existent pick order returns 404`() {
        given().`when`().get("/api/v1/pick-orders/99999999/pick-ticket.pdf").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["fulfillment-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick ticket is forbidden without fulfillment-read`() {
        given().`when`().get("/api/v1/pick-orders/1/pick-ticket.pdf").then().statusCode(403)
    }

    // ── Row 20 (V605) null-blast-radius pin: pick ticket renders for an EXTINGUISH order ────

    private fun createStockForExtinguish(): Long {
        seedPackStaging()
        val s = System.nanoTime()
        val iu = createItemUnit("OU-$s"); val num = "P-EXT-TICKET-$s"
        val pid = createProduct(num, iu); val ul = createUnitLoad("UL-EXT-TICKET-$s")
        return createStock(ul, pid, num, 25.0)
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick ticket renders for an EXTINGUISH order with no backing delivery order, showing a dash`() {
        val suId = createStockForExtinguish()
        val poId = given().contentType(ContentType.JSON)
            .body("""{"stockUnitIds":[$suId]}""")
            .`when`().post("/api/v1/pick-orders/extinguish").then().statusCode(201).extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/pick-orders/$poId/pick-ticket.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray().let {
                assertThat(String(it.copyOfRange(0, 4))).isEqualTo("%PDF")
            }
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
    fun `pick ticket with store=true archives the PDF under the pick order's client`() {
        val orderId = seedAndReleaseOrder()
        val poId = releaseToPicking(orderId)

        given().`when`().get("/api/v1/pick-orders/$poId/pick-ticket.pdf?store=true")
            .then().statusCode(200).contentType("application/pdf")

        val row = storedDocumentRepository
            .find("entityType = ?1 and entityId = ?2 and documentType = ?3", "pick-order", poId, "pick-ticket")
            .firstResult()
        assertThat(row).isNotNull
        assertThat(row!!.clientId).isEqualTo(1L)
        assertThat(row.fileName).isEqualTo("pick-ticket-$poId.pdf")
    }

    @Test
    @TestSecurity(
        user = "ff",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write",
            "order-read", "order-write", "layout-read", "layout-write",
            "fulfillment-read", "fulfillment-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pick ticket without store defaults to not archiving`() {
        val orderId = seedAndReleaseOrder()
        val poId = releaseToPicking(orderId)

        given().`when`().get("/api/v1/pick-orders/$poId/pick-ticket.pdf")
            .then().statusCode(200).contentType("application/pdf")

        val count = storedDocumentRepository
            .count("entityType = ?1 and entityId = ?2 and documentType = ?3", "pick-order", poId, "pick-ticket")
        assertThat(count).isZero()
    }

    @Test
    fun `template renders SKU and source location`() {
        val html = renderer.render(
            "/templates/pick-ticket.html",
            mapOf(
                "pickOrder" to mapOf(
                    "pickOrderNumber" to "PO-1",
                    "deliveryOrderNumber" to "DO-1",
                    "prio" to "50",
                    "operatorId" to "op1",
                    "state" to "RELEASED",
                ),
                "picks" to listOf(
                    mapOf(
                        "seq" to "1",
                        "itemDataNumber" to "SKU-7",
                        "lotNumber" to null,
                        "plannedAmount" to "10",
                        "sourceLocation" to "A-01-01",
                        "sourceUnitLoad" to "UL-1",
                    ),
                ),
                "generatedAt" to "2026-07-25T00:00:00Z",
            ),
        )
        assertThat(html).contains("SKU-7").contains("A-01-01").contains("UL-1").contains("PO-1")
    }

    @Test
    fun `template escapes an XSS attempt in itemDataNumber`() {
        val html = renderer.render(
            "/templates/pick-ticket.html",
            mapOf(
                "pickOrder" to mapOf(
                    "pickOrderNumber" to "PO-1",
                    "deliveryOrderNumber" to "DO-1",
                    "prio" to "50",
                    "operatorId" to "op1",
                    "state" to "RELEASED",
                ),
                "picks" to listOf(
                    mapOf(
                        "seq" to "1",
                        "itemDataNumber" to "<img src=x onerror=alert(1)>",
                        "lotNumber" to null,
                        "plannedAmount" to "10",
                        "sourceLocation" to "A-01-01",
                        "sourceUnitLoad" to "UL-1",
                    ),
                ),
                "generatedAt" to "2026-07-25T00:00:00Z",
            ),
        )
        assertThat(html).doesNotContain("<img src=x")
        assertThat(html).contains("&lt;img src=x")
    }

    @Test
    fun `template renders a dash for a missing source stock unit`() {
        val html = renderer.render(
            "/templates/pick-ticket.html",
            mapOf(
                "pickOrder" to mapOf(
                    "pickOrderNumber" to "PO-1",
                    "deliveryOrderNumber" to "DO-1",
                    "prio" to "50",
                    "operatorId" to "op1",
                    "state" to "RELEASED",
                ),
                "picks" to listOf(
                    mapOf(
                        "seq" to "1",
                        "itemDataNumber" to "SKU-9",
                        "lotNumber" to null,
                        "plannedAmount" to "10",
                        "sourceLocation" to "—",
                        "sourceUnitLoad" to "—",
                    ),
                ),
                "generatedAt" to "2026-07-25T00:00:00Z",
            ),
        )
        assertThat(html).contains("SKU-9").contains("—")
    }
}

package com.karyo.orders

import com.karyo.docstore.repository.StoredDocumentRepository
import com.karyo.documents.DocumentRenderer
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.service.OrderDocumentService
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.security.TenantContext
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
import org.junit.jupiter.api.Test

/**
 * D7: delivery note PDF — ordered-vs-picked reconciliation, gated on
 * [com.karyo.orders.vo.OrderState.PICKED]. Three tiers, mirroring
 * [com.karyo.fulfillment.PickTicketRestTest] / [com.karyo.fulfillment.ShipmentDocumentServiceTest]:
 *  - REST: status codes + content-type (magic-byte PDF check + the 409 gate + 404/403).
 *  - Service-level (real DB, real [com.karyo.fulfillment.spi.PickRollupLookup]): proves the
 *    ACTUAL arithmetic (priced-only total, unpriced footnote) via PDFBox text extraction —
 *    the render-level tests below only prove the template displays whatever it's handed,
 *    not that [OrderDocumentService] computes it correctly.
 *  - Render-level ([DocumentRenderer] direct, hand-fed maps): template display/escaping.
 *
 * The commercial template-override integration is isolated in `DeliveryNoteTemplateOverrideTest`.
 */
@QuarkusTest
class DeliveryNoteRestTest {

    @Inject lateinit var renderer: DocumentRenderer
    @Inject lateinit var progression: OrderProgressionPort
    @Inject lateinit var documentService: OrderDocumentService
    @Inject lateinit var orderRepository: DeliveryOrderRepository
    @Inject lateinit var pickRepository: PickRepository
    @Inject lateinit var pickOrderRepository: PickOrderRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var storedDocumentRepository: StoredDocumentRepository

    // ── REST seeding helpers (lifted from OrderProgressionPortTest/PickTicketRestTest) ──

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Delivery Note Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"labelId":"$label","unitLoadTypeId":1,"storageLocationId":100,"storageLocationName":"A-01-01"}""")
            .`when`().post("/api/v1/unit-loads").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStock(unitLoadId: Long, itemDataId: Long, itemNumber: String, amount: Double): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber","amount":$amount,""" +
                    """"unitLoadId":$unitLoadId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201).extract().jsonPath().getLong("id")

    private fun seedProductWithStock(suffix: Long, stockAmount: Double): Long {
        val itemUnitId = createItemUnit("DN-IU-${suffix.toString().takeLast(10)}")
        val number = "DN-SKU-$suffix"
        val productId = createProduct(number, itemUnitId)
        val unitLoadId = createUnitLoad("UL-DN-$suffix")
        createStock(unitLoadId, productId, number, stockAmount)
        return productId
    }

    /** Seeds one product with 100 stock, creates+releases an order for [amount]. Returns the order id. */
    private fun seedAndReleaseOrderFor(amount: Double): Long {
        val suffix = System.nanoTime()
        val productId = seedProductWithStock(suffix, stockAmount = 100.0)
        val orderId = given().contentType(ContentType.JSON)
            .body("""{"customerName":"Acme Note Co","lines":[{"itemDataId":$productId,"amount":$amount}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId
    }

    /** Creates (but does NOT release) an order — stays CREATED, below the PICKED gate. */
    private fun seedCreatedOrder(): Long {
        val suffix = System.nanoTime()
        val productId = seedProductWithStock(suffix, stockAmount = 10.0)
        return given().contentType(ContentType.JSON)
            .body("""{"customerName":"Acme Note Co","lines":[{"itemDataId":$productId,"amount":5.0}]}""")
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Seeds a two-line order (both against the same product, independently reservable) and releases it. */
    private fun seedAndReleaseTwoLineOrder(amount1: Double, amount2: Double): Pair<Long, List<Long>> {
        val suffix = System.nanoTime()
        val p1 = seedProductWithStock(suffix, stockAmount = 100.0)
        val p2 = seedProductWithStock(suffix + 1, stockAmount = 100.0)
        val body = given().contentType(ContentType.JSON)
            .body(
                """{"customerName":"Acme Note Co","lines":[""" +
                    """{"itemDataId":$p1,"amount":$amount1},{"itemDataId":$p2,"amount":$amount2}]}""",
            )
            .`when`().post("/api/v1/delivery-orders").then().statusCode(201).extract()
        val orderId = body.jsonPath().getLong("id")
        val lineIds = body.jsonPath().getList("lines.id", Long::class.java)
        given().`when`().post("/api/v1/delivery-orders/$orderId/release").then().statusCode(200)
        return orderId to lineIds
    }

    /** Sets a line's unitPrice directly — no REST field exists for it at create time (honest null default). */
    @Transactional
    fun setUnitPrice(orderId: Long, lineId: Long, price: BigDecimal) {
        val order = orderRepository.findByIdAndClient(orderId, 1L)!!
        order.lines.first { it.id == lineId }.unitPrice = price
    }

    /**
     * Persists a real PICKED [Pick] row against [lineId] (mirroring
     * [com.karyo.fulfillment.PickRollupLookupTest]'s `persistPick`) — exercises the REAL
     * [com.karyo.fulfillment.spi.PickRollupLookup] the service consumes, rather than mocking it.
     */
    @Transactional
    fun persistPick(lineId: Long, picked: String) {
        val parent = PickOrder().apply {
            clientId = 1L
            pickOrderNumber = "PO-D7-${System.nanoTime()}"
            deliveryOrderId = lineId
            deliveryOrderNumber = "ORD-D7"
            state = PickState.RELEASED.code
        }
        pickOrderRepository.persist(parent)
        pickRepository.persist(
            Pick().apply {
                clientId = 1L
                pickOrderId = parent.id!!
                deliveryOrderLineId = lineId
                itemDataId = 1L
                itemDataNumber = "SKU-D7"
                sourceStockUnitId = 1L
                plannedAmount = BigDecimal(picked)
                pickedAmount = BigDecimal(picked)
                state = PickState.PICKED.code
                pickingType = PickingType.PICK.name
            },
        )
    }

    // ── REST: status codes + content-type ───────────────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note renders for a PICKED order`() {
        val orderId = seedAndReleaseOrderFor(60.0)
        progression.markPicked(orderId, 1L)

        given().`when`().get("/api/v1/delivery-orders/$orderId/delivery-note.pdf")
            .then().statusCode(200).contentType("application/pdf")
            .extract().asByteArray().let {
                assertThat(String(it.copyOfRange(0, 4))).isEqualTo("%PDF")
            }
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note for a CREATED order returns 409 — nothing to reconcile before picking`() {
        val orderId = seedCreatedOrder()

        given().`when`().get("/api/v1/delivery-orders/$orderId/delivery-note.pdf")
            .then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note for an unknown order returns 404`() {
        given().`when`().get("/api/v1/delivery-orders/99999999/delivery-note.pdf").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note is forbidden without order-read`() {
        given().`when`().get("/api/v1/delivery-orders/1/delivery-note.pdf").then().statusCode(403)
    }

    // ── Task 2: ?store=true opt-in archiving (docstore-templates sprint) ────────────────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note with store=true archives the PDF under the order's client`() {
        val orderId = seedAndReleaseOrderFor(60.0)
        progression.markPicked(orderId, 1L)

        given().`when`().get("/api/v1/delivery-orders/$orderId/delivery-note.pdf?store=true")
            .then().statusCode(200).contentType("application/pdf")

        val row = storedDocumentRepository
            .find("entityType = ?1 and entityId = ?2 and documentType = ?3", "delivery-order", orderId, "delivery-note")
            .firstResult()
        assertThat(row).isNotNull
        assertThat(row!!.clientId).isEqualTo(1L)
        assertThat(row.fileName).isEqualTo("delivery-note-$orderId.pdf")
    }

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note without store defaults to not archiving`() {
        val orderId = seedAndReleaseOrderFor(60.0)
        progression.markPicked(orderId, 1L)

        given().`when`().get("/api/v1/delivery-orders/$orderId/delivery-note.pdf")
            .then().statusCode(200).contentType("application/pdf")

        val count = storedDocumentRepository
            .count("entityType = ?1 and entityId = ?2 and documentType = ?3", "delivery-order", orderId, "delivery-note")
        assertThat(count).isZero()
    }

    // ── Service-level: real PickRollupLookup + real arithmetic ──────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["product-read", "product-write", "inventory-read", "inventory-write", "order-read", "order-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `delivery note sums priced lines only and foots the unpriced-lines footnote`() {
        tenantContext.clientId = 1L
        val (orderId, lineIds) = seedAndReleaseTwoLineOrder(amount1 = 10.0, amount2 = 5.0)
        setUnitPrice(orderId, lineIds[0], BigDecimal("5.00")) // line 2 stays unpriced (null)
        persistPick(lineIds[0], "7.000") // short-pick: 7 of 10
        persistPick(lineIds[1], "5.000") // fully picked
        progression.markPicked(orderId, 1L)

        val pdf = documentService.deliveryNotePdf(orderId)
        assertThat(String(pdf.copyOfRange(0, 4))).isEqualTo("%PDF")
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }

        // 10 * 5.00 = 50.00 — line 2 (unpriced) contributes nothing to the total.
        assertThat(text).contains("50.00")
        assertThat(text).contains("unpriced lines excluded from total")
    }

    // ── Render-level: DocumentRenderer direct, hand-fed maps (template display only) ──
    // The bundled template now carries a sender block (Task 7's `{#if hasSender}`), so the
    // real field set below includes the hasSender/senderName pair. Qute rendering is strict:
    // a missing key is a render error, not a blank.

    @Test
    fun `template reconciles ordered vs picked quantities`() {
        val html = renderer.render(
            "/templates/delivery-note.html",
            mapOf(
                "orderNumber" to "DO-1",
                "customerName" to "Acme",
                "street" to "Main St", "streetNumber" to "1", "zipCode" to "12345",
                "city" to "Springfield", "country" to "US",
                "deliveryDate" to "2026-08-01",
                "generatedAt" to "2026-07-25T00:00:00Z",
                "hasSender" to false, "senderName" to "",
                "lines" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-1", "externalNumber" to "—", "lotNumber" to "—",
                        "ordered" to "10", "picked" to "7", "substituted" to "—",
                        "unitPrice" to "5.00", "lineTotal" to "50.00",
                    ),
                ),
                "total" to "50.00", "hasUnpriced" to false, "hasNotes" to false, "notes" to "",
            ),
        )
        assertThat(html).contains(">10<").contains(">7<")
    }

    @Test
    fun `template shows a dash and the footnote for an unpriced line`() {
        val html = renderer.render(
            "/templates/delivery-note.html",
            mapOf(
                "orderNumber" to "DO-2", "customerName" to "Acme",
                "street" to "", "streetNumber" to "", "zipCode" to "", "city" to "", "country" to "",
                "deliveryDate" to "—", "generatedAt" to "2026-07-25T00:00:00Z",
                "hasSender" to false, "senderName" to "",
                "lines" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-2", "externalNumber" to "—", "lotNumber" to "—",
                        "ordered" to "10", "picked" to "10", "substituted" to "—",
                        "unitPrice" to "—", "lineTotal" to "—",
                    ),
                ),
                "total" to "0.00", "hasUnpriced" to true, "hasNotes" to false, "notes" to "",
            ),
        )
        assertThat(html).contains(">—<")
        assertThat(html).contains("unpriced lines excluded from total")
    }

    @Test
    fun `template omits the footnote when every line is priced`() {
        val html = renderer.render(
            "/templates/delivery-note.html",
            mapOf(
                "orderNumber" to "DO-4", "customerName" to "Acme",
                "street" to "", "streetNumber" to "", "zipCode" to "", "city" to "", "country" to "",
                "deliveryDate" to "—", "generatedAt" to "2026-07-25T00:00:00Z",
                "hasSender" to false, "senderName" to "",
                "lines" to listOf(
                    mapOf(
                        "itemDataNumber" to "SKU-4", "externalNumber" to "—", "lotNumber" to "—",
                        "ordered" to "10", "picked" to "10", "substituted" to "—",
                        "unitPrice" to "5.00", "lineTotal" to "50.00",
                    ),
                ),
                "total" to "50.00", "hasUnpriced" to false, "hasNotes" to false, "notes" to "",
            ),
        )
        assertThat(html).doesNotContain("unpriced lines excluded from total")
    }

    @Test
    fun `template escapes an XSS attempt in customerName`() {
        val html = renderer.render(
            "/templates/delivery-note.html",
            mapOf(
                "orderNumber" to "DO-3", "customerName" to "<img src=x onerror=alert(1)>",
                "street" to "", "streetNumber" to "", "zipCode" to "", "city" to "", "country" to "",
                "deliveryDate" to "—", "generatedAt" to "2026-07-25T00:00:00Z",
                "hasSender" to false, "senderName" to "",
                "lines" to emptyList<Map<String, Any?>>(),
                "total" to "0.00", "hasUnpriced" to false, "hasNotes" to false, "notes" to "",
            ),
        )
        assertThat(html).doesNotContain("<img src=x")
        assertThat(html).contains("&lt;img src=x")
    }
}

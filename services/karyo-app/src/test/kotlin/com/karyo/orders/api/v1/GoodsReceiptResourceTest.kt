package com.karyo.orders.api.v1

import com.karyo.common.pagination.PageMetadata
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.orders.dto.AsnRefResponse
import com.karyo.orders.dto.CreateGoodsReceiptRequest
import com.karyo.orders.dto.GoodsReceiptLineResponse
import com.karyo.orders.dto.GoodsReceiptResponse
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.dto.ReceiveLineResponse
import com.karyo.orders.exception.OrderException
import com.karyo.orders.service.GoodsReceiptService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import java.math.BigDecimal

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = org.mockito.ArgumentMatchers.any<T>() ?: null as T

private fun anyLong(): Long = org.mockito.ArgumentMatchers.anyLong()

/** Kotlin-safe eq matcher for non-null reference params (same trick as [anyObj]). */
private fun <T> eqObj(value: T): T = org.mockito.ArgumentMatchers.eq(value) ?: value

@QuarkusTest
class GoodsReceiptResourceTest {

    @InjectMock
    lateinit var receiptService: GoodsReceiptService

    private fun sampleLine(qaHold: Boolean = false, reversed: Boolean = false) = GoodsReceiptLineResponse(
        id = 21L,
        asnLineId = 11L,
        itemDataId = 100L,
        itemDataNumber = "SKU-001",
        amount = BigDecimal("10"),
        locationId = 100L,
        locationName = "DOCK-01",
        unitLoadLabel = "UL-GR-1",
        stockUnitId = 31L,
        unitLoadId = 41L,
        lotNumber = null,
        bestBefore = null,
        qaHold = qaHold,
        reversed = reversed,
        reversedAt = if (reversed) "2026-07-24T00:00:00Z" else null,
    )

    private fun sampleReceipt(state: Int = 50, lines: List<GoodsReceiptLineResponse> = emptyList()) =
        GoodsReceiptResponse(
            id = 1L,
            receiptNumber = "GR-1001",
            asns = listOf(AsnRefResponse(id = 5L, asnNumber = "ASN-1001")),
            carrierName = "DHL",
            deliveryNoteNumber = "DN-7",
            notes = null,
            state = state,
            stateName = "CREATED",
            clientId = 1L,
            lines = lines,
            created = "2026-06-12T00:00:00Z",
            modified = "2026-06-12T00:00:00Z",
        )

    private fun receiveRequest(allowOverReceipt: Boolean = false) = ReceiveLineRequest(
        asnLineId = 11L,
        amount = BigDecimal("10"),
        locationId = 100L,
        locationName = "DOCK-01",
        allowOverReceipt = allowOverReceipt,
    )

    // ── Create ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can open receipt bound to ASN - 201`() {
        doReturn(sampleReceipt()).`when`(receiptService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateGoodsReceiptRequest(asnId = 5L, carrierName = "DHL"))
            .`when`().post("/api/v1/goods-receipts")
            .then()
            .statusCode(201)
            .body("receiptNumber", `is`("GR-1001"))
            .body("asns[0].asnNumber", `is`("ASN-1001"))
            .body("state", `is`(50))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `binding to non-receivable ASN returns 409`() {
        doThrow(OrderException.AsnNotReceivable(5L, 50))
            .`when`(receiptService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateGoodsReceiptRequest(asnId = 5L))
            .`when`().post("/api/v1/goods-receipts")
            .then()
            .statusCode(409)
    }

    // ── List / Get ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list receipts with state and asn filters - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleReceipt()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(receiptService).list(anyLong(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/goods-receipts?state=50&asnId=5")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].receiptNumber", `is`("GR-1001"))
            .body("page.totalElements", `is`(1))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get receipt with lines - 200`() {
        doReturn(sampleReceipt(state = 500, lines = listOf(sampleLine())))
            .`when`(receiptService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/goods-receipts/1")
            .then()
            .statusCode(200)
            .body("id", `is`(1))
            .body("lines", hasSize<Any>(1))
            .body("lines[0].stockUnitId", `is`(31))
            .body("lines[0].qaHold", `is`(false))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `missing receipt returns 404`() {
        doThrow(OrderException.NotFound("GoodsReceipt", "id=999"))
            .`when`(receiptService).findById(999L, 1L)

        given()
            .`when`().get("/api/v1/goods-receipts/999")
            .then()
            .statusCode(404)
    }

    // ── Receive line ─────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can receive line - 201 with created ids`() {
        val response = ReceiveLineResponse(
            receipt = sampleReceipt(state = 500, lines = listOf(sampleLine())),
            lineId = 21L,
            stockUnitId = 31L,
            unitLoadId = 41L,
            unitLoadLabel = "UL-GR-1",
        )
        doReturn(response).`when`(receiptService).receiveLine(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(receiveRequest())
            .`when`().post("/api/v1/goods-receipts/1/lines")
            .then()
            .statusCode(201)
            .body("receipt.state", `is`(500))
            .body("lineId", `is`(21))
            .body("stockUnitId", `is`(31))
            .body("unitLoadId", `is`(41))
            .body("unitLoadLabel", `is`("UL-GR-1"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `over-receipt returns 409 with over-receipt problem type`() {
        doThrow(OrderException.OverReceipt(11L, BigDecimal("10"), BigDecimal("8"), BigDecimal("5")))
            .`when`(receiptService).receiveLine(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(receiveRequest())
            .`when`().post("/api/v1/goods-receipts/1/lines")
            .then()
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/over-receipt"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `allowOverReceipt override succeeds - 201`() {
        val response = ReceiveLineResponse(
            receipt = sampleReceipt(state = 500, lines = listOf(sampleLine())),
            lineId = 21L,
            stockUnitId = 31L,
            unitLoadId = 41L,
            unitLoadLabel = "UL-GR-1",
        )
        doReturn(response).`when`(receiptService).receiveLine(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(receiveRequest(allowOverReceipt = true))
            .`when`().post("/api/v1/goods-receipts/1/lines")
            .then()
            .statusCode(201)
            .body("lineId", `is`(21))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `receiving on finished receipt returns 409`() {
        doThrow(OrderException.ReceiptNotReceivable(1L, 700))
            .`when`(receiptService).receiveLine(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(receiveRequest())
            .`when`().post("/api/v1/goods-receipts/1/lines")
            .then()
            .statusCode(409)
    }

    // ── Finish / Cancel ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can finish receipt - 200`() {
        doReturn(sampleReceipt(state = 700, lines = listOf(sampleLine())))
            .`when`(receiptService).finish(1L, 1L)

        given()
            .`when`().post("/api/v1/goods-receipts/1/finish")
            .then()
            .statusCode(200)
            .body("state", `is`(700))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can cancel empty receipt - 200`() {
        doReturn(sampleReceipt(state = 800).copy(asns = emptyList()))
            .`when`(receiptService).cancel(1L, 1L)

        given()
            .`when`().post("/api/v1/goods-receipts/1/cancel")
            .then()
            .statusCode(200)
            .body("state", `is`(800))
            .body("asns", hasSize<Any>(0))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel with received lines returns 409`() {
        doThrow(OrderException.NotCancelable("GoodsReceipt", 1L, "1 line(s) were already received"))
            .`when`(receiptService).cancel(1L, 1L)

        given()
            .`when`().post("/api/v1/goods-receipts/1/cancel")
            .then()
            .statusCode(409)
    }

    // ── B7: claim / release / pause / resume ─────────────────────────────
    // Bodiless action POSTs send an explicit JSON content type — without it the
    // request 415s before @RolesAllowed even runs.

    @Test
    @TestSecurity(user = "op-resource", roles = ["order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claim passes the caller username as operatorId - 200`() {
        doReturn(sampleReceipt()).`when`(receiptService).claim(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/claim")
            .then()
            .statusCode(200)
        org.mockito.Mockito.verify(receiptService)
            .claim(org.mockito.ArgumentMatchers.eq(1L), eqObj("op-resource"), org.mockito.ArgumentMatchers.eq(1L))
    }

    @Test
    @TestSecurity(user = "op-resource", roles = ["order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `claim conflict maps to 409 receipt-claim-conflict`() {
        doThrow(OrderException.ReceiptClaimConflict(1L, "already claimed by 'someone-else'"))
            .`when`(receiptService).claim(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/claim")
            .then()
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-claim-conflict"))
    }

    @Test
    @TestSecurity(user = "mgr-resource", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release derives asManager true from the MANAGER role`() {
        doReturn(sampleReceipt()).`when`(receiptService)
            .release(anyLong(), anyObj(), org.mockito.ArgumentMatchers.anyBoolean(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/release")
            .then()
            .statusCode(200)
        org.mockito.Mockito.verify(receiptService).release(
            org.mockito.ArgumentMatchers.eq(1L),
            eqObj("mgr-resource"),
            org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.eq(1L),
        )
    }

    @Test
    @TestSecurity(user = "op-resource", roles = ["order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release derives asManager false without the MANAGER role`() {
        doReturn(sampleReceipt()).`when`(receiptService)
            .release(anyLong(), anyObj(), org.mockito.ArgumentMatchers.anyBoolean(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/release")
            .then()
            .statusCode(200)
        org.mockito.Mockito.verify(receiptService).release(
            org.mockito.ArgumentMatchers.eq(1L),
            eqObj("op-resource"),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq(1L),
        )
    }

    @Test
    @TestSecurity(user = "op-resource", roles = ["order-read", "order-write", "OPERATOR"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `pause and resume map conflicts to 409`() {
        doThrow(OrderException.ReceiptPauseConflict(1L, "already paused"))
            .`when`(receiptService).pause(anyLong(), anyLong())
        doThrow(OrderException.ReceiptPauseConflict(1L, "receipt is not paused"))
            .`when`(receiptService).resume(anyLong(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/pause")
            .then()
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-pause-conflict"))
        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/resume")
            .then()
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-pause-conflict"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot claim or pause - 403`() {
        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/claim")
            .then()
            .statusCode(403)
        given()
            .contentType(ContentType.JSON)
            .`when`().post("/api/v1/goods-receipts/1/pause")
            .then()
            .statusCode(403)
    }

    // ── B3: reverse a received line ──────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can reverse a line - 200 with reversed flag`() {
        doReturn(sampleReceipt(state = 500, lines = listOf(sampleLine(reversed = true))))
            .`when`(receiptService).reverseLine(1L, 21L, 1L)

        given()
            .`when`().delete("/api/v1/goods-receipts/1/lines/21")
            .then()
            .statusCode(200)
            .body("lines[0].reversed", `is`(true))
            .body("lines[0].reversedAt", `is`("2026-07-24T00:00:00Z"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse on closed receipt returns 409 not-cancelable`() {
        doThrow(OrderException.NotCancelable("GoodsReceiptLine", 21L, "receipt is closed (state 700)"))
            .`when`(receiptService).reverseLine(anyLong(), anyLong(), anyLong())

        given()
            .`when`().delete("/api/v1/goods-receipts/1/lines/21")
            .then()
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/not-cancelable"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reverse while paused returns 409 receipt-paused`() {
        doThrow(OrderException.ReceiptPaused(1L))
            .`when`(receiptService).reverseLine(anyLong(), anyLong(), anyLong())

        given()
            .`when`().delete("/api/v1/goods-receipts/1/lines/21")
            .then()
            .statusCode(409)
            .body("type", `is`("https://karyo.com/errors/receipt-paused"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot reverse a line - 403`() {
        given()
            .`when`().delete("/api/v1/goods-receipts/1/lines/21")
            .then()
            .statusCode(403)
    }

    // ── RBAC ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot receive lines - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(receiveRequest())
            .`when`().post("/api/v1/goods-receipts/1/lines")
            .then()
            .statusCode(403)
    }

    @Test
    fun `unauthenticated request gets 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateGoodsReceiptRequest())
            .`when`().post("/api/v1/goods-receipts")
            .then()
            .statusCode(401)
    }
}

package com.karyo.orders.api.v1

import com.karyo.common.pagination.PageMetadata
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.orders.dto.AsnFinishResponse
import com.karyo.orders.dto.AsnLineResponse
import com.karyo.orders.dto.AsnLineShortage
import com.karyo.orders.dto.AsnResponse
import com.karyo.orders.dto.CreateAsnLineRequest
import com.karyo.orders.dto.CreateAsnRequest
import com.karyo.orders.dto.UpdateAsnRequest
import com.karyo.orders.exception.OrderException
import com.karyo.orders.service.AsnService
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

@QuarkusTest
class AsnResourceTest {

    @InjectMock
    lateinit var asnService: AsnService

    private fun sampleLine(state: Int = 50, received: String = "0") = AsnLineResponse(
        id = 11L,
        lineNumber = 1,
        itemDataId = 100L,
        itemDataNumber = "SKU-001",
        expectedAmount = BigDecimal("10"),
        receivedAmount = BigDecimal(received),
        remainingAmount = BigDecimal("10").subtract(BigDecimal(received)),
        progressPercent = BigDecimal(received).multiply(BigDecimal(10)).toInt(),
        state = state,
        stateName = "CREATED",
        lotNumber = null,
    )

    private fun sampleAsn(state: Int = 50, lineState: Int = 50) = AsnResponse(
        id = 1L,
        asnNumber = "ASN-1001",
        externalNumber = "PO-555",
        carrierName = "DHL",
        expectedDate = null,
        notes = null,
        state = state,
        stateName = "CREATED",
        clientId = 1L,
        progressPercent = 0,
        lines = listOf(sampleLine(state = lineState)),
        created = "2026-06-12T00:00:00Z",
        modified = "2026-06-12T00:00:00Z",
    )

    private fun validCreateRequest() = CreateAsnRequest(
        carrierName = "DHL",
        lines = listOf(CreateAsnLineRequest(itemDataId = 100L, expectedAmount = BigDecimal("10"))),
    )

    // ── Create ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create ASN with lines - 201`() {
        doReturn(sampleAsn()).`when`(asnService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/asns")
            .then()
            .statusCode(201)
            .body("asnNumber", `is`("ASN-1001"))
            .body("state", `is`(50))
            .body("lines", hasSize<Any>(1))
            .body("lines[0].expectedAmount", `is`(10))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with unknown product returns 404`() {
        doThrow(OrderException.InvalidReference("Product", "id=999"))
            .`when`(asnService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/asns")
            .then()
            .statusCode(404)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with empty lines returns 400`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"carrierName":"DHL","lines":[]}""")
            .`when`().post("/api/v1/asns")
            .then()
            .statusCode(400)
    }

    // ── List ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list ASNs paginated - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleAsn()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(asnService).list(anyLong(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/asns?page=0&size=20")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].asnNumber", `is`("ASN-1001"))
            .body("page.totalElements", `is`(1))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list ASNs with state filter and text query - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleAsn()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(asnService).list(anyLong(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/asns?state=50&q=po-555")
            .then()
            .statusCode(200)
            .body("content[0].state", `is`(50))
    }

    // ── Get ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get ASN by id with progress - 200`() {
        doReturn(sampleAsn()).`when`(asnService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/asns/1")
            .then()
            .statusCode(200)
            .body("id", `is`(1))
            .body("asnNumber", `is`("ASN-1001"))
            .body("progressPercent", `is`(0))
            .body("lines[0].remainingAmount", `is`(10))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `missing ASN returns 404`() {
        doThrow(OrderException.NotFound("Asn", "id=999"))
            .`when`(asnService).findById(999L, 1L)

        given()
            .`when`().get("/api/v1/asns/999")
            .then()
            .statusCode(404)
    }

    // ── Update (pre-release only) ────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can update ASN while CREATED - 200`() {
        doReturn(sampleAsn()).`when`(asnService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(UpdateAsnRequest(carrierName = "UPS"))
            .`when`().put("/api/v1/asns/1")
            .then()
            .statusCode(200)
            .body("asnNumber", `is`("ASN-1001"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update after release returns 409`() {
        doThrow(OrderException.NotEditable(1L, 100, "ASN"))
            .`when`(asnService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(UpdateAsnRequest(carrierName = "Too Late"))
            .`when`().put("/api/v1/asns/1")
            .then()
            .statusCode(409)
    }

    // ── Lifecycle actions ────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can release ASN - 200`() {
        doReturn(sampleAsn(state = 100)).`when`(asnService).release(1L, 1L)

        given()
            .`when`().post("/api/v1/asns/1/release")
            .then()
            .statusCode(200)
            .body("state", `is`(100))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release of already-released ASN returns 409`() {
        doThrow(OrderException.InvalidTransition(1L, 100, 100))
            .`when`(asnService).release(1L, 1L)

        given()
            .`when`().post("/api/v1/asns/1/release")
            .then()
            .statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can cancel ASN pre-STARTED - 200`() {
        doReturn(sampleAsn(state = 800)).`when`(asnService).cancel(1L, 1L)

        given()
            .`when`().post("/api/v1/asns/1/cancel")
            .then()
            .statusCode(200)
            .body("state", `is`(800))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel after receipts started returns 409`() {
        doThrow(OrderException.NotCancelable("ASN", 1L, "goods were already received against it (state 500)"))
            .`when`(asnService).cancel(1L, 1L)

        given()
            .`when`().post("/api/v1/asns/1/cancel")
            .then()
            .statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can force-finish ASN - 200 with shortage summary`() {
        val response = AsnFinishResponse(
            asn = sampleAsn(state = 700, lineState = 700),
            shortages = listOf(
                AsnLineShortage(
                    lineId = 11L,
                    lineNumber = 1,
                    itemDataId = 100L,
                    itemDataNumber = "SKU-001",
                    expectedAmount = BigDecimal("10"),
                    receivedAmount = BigDecimal("4"),
                    shortfall = BigDecimal("6"),
                )
            ),
        )
        doReturn(response).`when`(asnService).finish(1L, 1L)

        given()
            .`when`().post("/api/v1/asns/1/finish")
            .then()
            .statusCode(200)
            .body("asn.state", `is`(700))
            .body("shortages", hasSize<Any>(1))
            .body("shortages[0].shortfall", `is`(6))
    }

    // ── RBAC ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot create ASN - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/asns")
            .then()
            .statusCode(403)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot release ASN - 403`() {
        given()
            .`when`().post("/api/v1/asns/1/release")
            .then()
            .statusCode(403)
    }

    @Test
    fun `unauthenticated request gets 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/asns")
            .then()
            .statusCode(401)
    }
}

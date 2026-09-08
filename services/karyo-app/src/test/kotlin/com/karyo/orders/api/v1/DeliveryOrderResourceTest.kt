package com.karyo.orders.api.v1

import com.karyo.common.pagination.PageMetadata
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.patch.Patchable
import com.karyo.orders.dto.CreateDeliveryOrderLineRequest
import com.karyo.orders.dto.CreateDeliveryOrderRequest
import com.karyo.orders.dto.DeliveryOrderLineResponse
import com.karyo.orders.dto.DeliveryOrderReleaseResponse
import com.karyo.orders.dto.DeliveryOrderResponse
import com.karyo.orders.dto.UpdateDeliveryOrderRequest
import com.karyo.orders.exception.OrderException
import com.karyo.orders.service.OrderService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
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
class DeliveryOrderResourceTest {

    @InjectMock
    lateinit var orderService: OrderService

    private fun sampleLine(state: Int = 50, reserved: String = "0") = DeliveryOrderLineResponse(
        id = 11L,
        lineNumber = 1,
        itemDataId = 100L,
        itemDataNumber = "SKU-001",
        amount = BigDecimal("10"),
        reservedAmount = BigDecimal(reserved),
        shortage = BigDecimal("10").subtract(BigDecimal(reserved)),
        state = state,
        stateName = "CREATED",
        lotNumber = null,
        itemDataName = "Widget",
        unitPrice = BigDecimal("12.99"),
    )

    private fun sampleOrder(state: Int = 50, lineState: Int = 50) = DeliveryOrderResponse(
        id = 1L,
        orderNumber = "DO-1001",
        externalNumber = null,
        customerName = "ACME Corp",
        street = null,
        streetNumber = null,
        zipCode = null,
        city = null,
        country = null,
        phone = null,
        email = null,
        deliveryDate = null,
        prio = 50,
        notes = null,
        state = state,
        stateName = "CREATED",
        orderStrategyId = null,
        clientId = 1L,
        lines = listOf(sampleLine(state = lineState)),
        created = "2026-06-12T00:00:00Z",
        modified = "2026-06-12T00:00:00Z",
        carrierName = null,
        carrierService = null,
        trackingNumber = null,
        shippedAt = null,
    )

    private fun validCreateRequest() = CreateDeliveryOrderRequest(
        customerName = "ACME Corp",
        lines = listOf(CreateDeliveryOrderLineRequest(itemDataId = 100L, amount = BigDecimal("10"))),
    )

    // ── Create ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create order with lines - 201`() {
        doReturn(sampleOrder()).`when`(orderService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/delivery-orders")
            .then()
            .statusCode(201)
            .body("orderNumber", `is`("DO-1001"))
            .body("state", `is`(50))
            .body("lines", hasSize<Any>(1))
            .body("lines[0].itemDataNumber", `is`("SKU-001"))
            .body("lines[0].itemDataName", `is`("Widget"))
            .body("lines[0].unitPrice", `is`(12.99f))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with unknown product returns 404`() {
        doThrow(OrderException.InvalidReference("Product", "id=999"))
            .`when`(orderService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/delivery-orders")
            .then()
            .statusCode(404)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with empty lines returns 400`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"ACME","lines":[]}""")
            .`when`().post("/api/v1/delivery-orders")
            .then()
            .statusCode(400)
    }

    // ── List ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list orders paginated - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleOrder()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(orderService).list(anyLong(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/delivery-orders?page=0&size=20")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].orderNumber", `is`("DO-1001"))
            .body("page.totalElements", `is`(1))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list orders with state filter and text query - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleOrder()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(orderService).list(anyLong(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/delivery-orders?state=50&q=acme")
            .then()
            .statusCode(200)
            .body("content[0].state", `is`(50))
    }

    // ── Get ──────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get order by id - 200`() {
        doReturn(sampleOrder()).`when`(orderService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/delivery-orders/1")
            .then()
            .statusCode(200)
            .body("id", `is`(1))
            .body("orderNumber", `is`("DO-1001"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `missing order returns 404`() {
        doThrow(OrderException.NotFound("DeliveryOrder", "id=999"))
            .`when`(orderService).findById(999L, 1L)

        given()
            .`when`().get("/api/v1/delivery-orders/999")
            .then()
            .statusCode(404)
    }

    // ── Update (pre-release only) ────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can update order while CREATED - 200`() {
        doReturn(sampleOrder()).`when`(orderService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            // Raw JSON, not the Kotlin object -- RestAssured's own client-side ObjectMapper
            // never sees the server's PatchableModule/JsonInclude wiring, so serializing
            // UpdateDeliveryOrderRequest() directly would emit `"notes":{}` for every
            // untouched Patchable<String> field (the pre-D2 default constructor still works
            // fine for non-Patchable fields like customerName).
            .body("""{"customerName":"New Name"}""")
            .`when`().put("/api/v1/delivery-orders/1")
            .then()
            .statusCode(200)
            .body("orderNumber", `is`("DO-1001"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update after release returns 409`() {
        doThrow(OrderException.NotEditable(1L, 100))
            .`when`(orderService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"Too Late"}""")
            .`when`().put("/api/v1/delivery-orders/1")
            .then()
            .statusCode(409)
    }

    // ── D2 tri-state deserialization (Patchable<String>) ─────────────────
    // These pin the DESERIALIZATION shape only (orderService is mocked, so business
    // behavior is proven separately by DeliveryOrderPatchSemanticsTest's real-DB round trip).
    // Inspects the real recorded invocation (mockingDetails) rather than ArgumentCaptor --
    // Kotlin's non-null return-type check on `ArgumentCaptor.capture()` throws immediately
    // against Mockito's null matcher-recording placeholder, which corrupts the matcher
    // stack for every later test in the same JVM.

    /** Last `update(...)` call's request argument, straight off the recorded invocation. */
    private fun lastUpdateRequest(): UpdateDeliveryOrderRequest {
        val call = org.mockito.Mockito.mockingDetails(orderService).invocations
            .last { it.method.name == "update" }
        return call.arguments[1] as UpdateDeliveryOrderRequest
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT omitting notes deserializes it to Patchable Absent`() {
        doReturn(sampleOrder()).`when`(orderService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"customerName":"New Name"}""")
            .`when`().put("/api/v1/delivery-orders/1")
            .then()
            .statusCode(200)

        assertThat(lastUpdateRequest().notes).isEqualTo(Patchable.Absent)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT with explicit null notes deserializes it to Patchable Null`() {
        doReturn(sampleOrder()).`when`(orderService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"notes":null}""")
            .`when`().put("/api/v1/delivery-orders/1")
            .then()
            .statusCode(200)

        assertThat(lastUpdateRequest().notes).isEqualTo(Patchable.Null)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT with a notes value deserializes it to Patchable Value`() {
        doReturn(sampleOrder()).`when`(orderService).update(anyLong(), anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body("""{"notes":"handle with care"}""")
            .`when`().put("/api/v1/delivery-orders/1")
            .then()
            .statusCode(200)

        assertThat(lastUpdateRequest().notes).isEqualTo(Patchable.Value("handle with care"))
    }

    // ── Lifecycle actions ────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can release order - 200 with shortage report`() {
        val response = DeliveryOrderReleaseResponse(
            order = sampleOrder(state = 300, lineState = 300),
            shortages = emptyList(),
        )
        doReturn(response).`when`(orderService).release(1L, 1L)

        given()
            .`when`().post("/api/v1/delivery-orders/1/release")
            .then()
            .statusCode(200)
            .body("order.state", `is`(300))
            .body("shortages", hasSize<Any>(0))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `release of already-released order returns 409`() {
        doThrow(OrderException.InvalidTransition(1L, 100, 100))
            .`when`(orderService).release(1L, 1L)

        given()
            .`when`().post("/api/v1/delivery-orders/1/release")
            .then()
            .statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["order-read", "order-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can cancel order - 200`() {
        doReturn(sampleOrder(state = 800)).`when`(orderService).cancel(1L, 1L)

        given()
            .`when`().post("/api/v1/delivery-orders/1/cancel")
            .then()
            .statusCode(200)
            .body("state", `is`(800))
    }

    // ── RBAC ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot create order - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/delivery-orders")
            .then()
            .statusCode(403)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["order-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `order-read role cannot release order - 403`() {
        given()
            .`when`().post("/api/v1/delivery-orders/1/release")
            .then()
            .statusCode(403)
    }

    @Test
    fun `unauthenticated request gets 401`() {
        given()
            .contentType(ContentType.JSON)
            .body(validCreateRequest())
            .`when`().post("/api/v1/delivery-orders")
            .then()
            .statusCode(401)
    }
}

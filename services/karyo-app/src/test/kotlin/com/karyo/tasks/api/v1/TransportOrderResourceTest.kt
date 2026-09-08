package com.karyo.tasks.api.v1

import com.karyo.common.pagination.PageMetadata
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.tasks.dto.AssignTransportOrderRequest
import com.karyo.tasks.dto.CompleteTransportOrderRequest
import com.karyo.tasks.dto.CreateTransportOrderRequest
import com.karyo.tasks.dto.TransportOrderResponse
import com.karyo.tasks.exception.TaskException
import com.karyo.tasks.service.TaskService
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
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T
private fun anyLong(): Long = ArgumentMatchers.anyLong()
private fun anyStr(): String = ArgumentMatchers.anyString()
private fun eqStr(v: String): String = ArgumentMatchers.eq(v) ?: v

@QuarkusTest
class TransportOrderResourceTest {

    @InjectMock
    lateinit var taskService: TaskService

    private fun sample(state: Int = 100, type: String = "PUTAWAY", pausedAt: String? = null) = TransportOrderResponse(
        id = 1L,
        orderNumber = "TO-1001",
        transportType = type,
        unitLoadId = 41L,
        unitLoadLabel = "UL-1",
        sourceLocationId = 900L,
        sourceLocationName = "DOCK-01",
        destinationLocationId = null,
        destinationLocationName = null,
        suggestedLocationId = 10L,
        suggestedLocationName = "A-01-01",
        state = state,
        stateName = "RELEASED",
        prio = 50,
        operatorId = null,
        executorType = "HUMAN",
        note = null,
        goodsReceiptLineId = 77L,
        clientId = 1L,
        created = "2026-06-13T00:00:00Z",
        modified = "2026-06-13T00:00:00Z",
        pausedAt = pausedAt,
    )

    // ── List / Get ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list transport orders with filters - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sample()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(taskService).list(anyLong(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj())

        given()
            .`when`().get("/api/v1/transport-orders?state=100&type=PUTAWAY")
            .then().statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].orderNumber", `is`("TO-1001"))
            .body("page.totalElements", `is`(1))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `paused=true returns only paused orders`() {
        val pausedOnly = PaginatedResponse(
            content = listOf(sample(pausedAt = "2026-08-14T00:00:00Z")),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(pausedOnly).`when`(taskService).list(anyLong(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), eq(true))

        given()
            .`when`().get("/api/v1/transport-orders?paused=true")
            .then().statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].pausedAt", `is`("2026-08-14T00:00:00Z"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `paused=false excludes paused orders`() {
        val notPaused = PaginatedResponse(
            content = listOf(sample()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(notPaused).`when`(taskService).list(anyLong(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), eq(false))

        given()
            .`when`().get("/api/v1/transport-orders?paused=false")
            .then().statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].pausedAt", org.hamcrest.Matchers.nullValue())
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `absent paused query param passes null through -- no clause`() {
        val all = PaginatedResponse(
            content = listOf(sample()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(all).`when`(taskService).list(anyLong(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), isNull())

        given()
            .`when`().get("/api/v1/transport-orders")
            .then().statusCode(200)
            .body("content", hasSize<Any>(1))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can get transport order - 200`() {
        doReturn(sample(state = 100)).`when`(taskService).findById(1L, 1L)

        given()
            .`when`().get("/api/v1/transport-orders/1")
            .then().statusCode(200)
            .body("id", `is`(1))
            .body("suggestedLocationName", `is`("A-01-01"))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `missing transport order returns 404`() {
        doThrow(TaskException.NotFound("TransportOrder", "id=999")).`when`(taskService).findById(999L, 1L)

        given().`when`().get("/api/v1/transport-orders/999").then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `invalid type filter returns 400`() {
        given().`when`().get("/api/v1/transport-orders?type=BOGUS").then().statusCode(400)
    }

    // ── Manual move ──────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create manual move - 201`() {
        doReturn(sample(type = "MOVE")).`when`(taskService).createManualMove(anyObj(), anyLong())

        given().contentType(ContentType.JSON)
            .body(CreateTransportOrderRequest(unitLoadId = 41L, destinationLocationId = 10L, destinationLocationName = "A-01-01"))
            .`when`().post("/api/v1/transport-orders")
            .then().statusCode(201)
            .body("transportType", `is`("MOVE"))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `manual move with missing unit load returns 404`() {
        doThrow(TaskException.InvalidReference("UnitLoad", "id=41")).`when`(taskService).createManualMove(anyObj(), anyLong())

        given().contentType(ContentType.JSON)
            .body(CreateTransportOrderRequest(unitLoadId = 41L, destinationLocationId = 10L))
            .`when`().post("/api/v1/transport-orders")
            .then().statusCode(404)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can assign task - 200`() {
        doReturn(sample(state = 400)).`when`(taskService).assign(anyLong(), anyStr(), anyLong())

        given().contentType(ContentType.JSON)
            .body(AssignTransportOrderRequest(operatorId = "op-7"))
            .`when`().post("/api/v1/transport-orders/1/assign")
            .then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `assign derives operator from the JWT and ignores a spoofed body operatorId`() {
        doReturn(sample(state = 400)).`when`(taskService).assign(anyLong(), anyStr(), anyLong())

        given().contentType(ContentType.JSON)
            .body(AssignTransportOrderRequest(operatorId = "someone-else"))
            .`when`().post("/api/v1/transport-orders/1/assign")
            .then().statusCode(200)

        org.mockito.Mockito.verify(taskService).assign(eq(1L), eqStr("manager"), eq(1L))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `assign works with no request body at all`() {
        doReturn(sample(state = 400)).`when`(taskService).assign(anyLong(), anyStr(), anyLong())

        given().`when`().post("/api/v1/transport-orders/1/assign")
            .then().statusCode(200)

        org.mockito.Mockito.verify(taskService).assign(eq(1L), eqStr("manager"), eq(1L))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can start task - 200`() {
        doReturn(sample(state = 500)).`when`(taskService).start(1L, 1L)

        given().`when`().post("/api/v1/transport-orders/1/start").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can complete task with override destination - 200`() {
        doReturn(sample(state = 700)).`when`(taskService).complete(anyLong(), anyObj(), anyLong())

        given().contentType(ContentType.JSON)
            .body(CompleteTransportOrderRequest(destinationLocationId = 20L, destinationLocationName = "B-02-02"))
            .`when`().post("/api/v1/transport-orders/1/complete")
            .then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `complete with no destination returns 409`() {
        doThrow(TaskException.NoDestination(1L)).`when`(taskService).complete(anyLong(), anyObj(), anyLong())

        given().contentType(ContentType.JSON).body("{}")
            .`when`().post("/api/v1/transport-orders/1/complete").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `complete with a negative amount returns 400 -- bean validation, never reaches TaskService`() {
        given().contentType(ContentType.JSON)
            .body(CompleteTransportOrderRequest(amount = java.math.BigDecimal("-5")))
            .`when`().post("/api/v1/transport-orders/1/complete")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `complete with a zero amount returns 400 -- bean validation, never reaches TaskService`() {
        given().contentType(ContentType.JSON)
            .body(CompleteTransportOrderRequest(amount = java.math.BigDecimal.ZERO))
            .`when`().post("/api/v1/transport-orders/1/complete")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can cancel task - 200`() {
        doReturn(sample(state = 800)).`when`(taskService).cancel(1L, 1L)

        given().`when`().post("/api/v1/transport-orders/1/cancel").then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["task-read", "task-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel a started task returns 409`() {
        doThrow(TaskException.NotCancelable(1L, "task is already started or closed (state 500)"))
            .`when`(taskService).cancel(1L, 1L)

        given().`when`().post("/api/v1/transport-orders/1/cancel").then().statusCode(409)
    }

    // ── RBAC ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["task-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `task-read role cannot create or mutate - 403`() {
        given().contentType(ContentType.JSON)
            .body(CreateTransportOrderRequest(unitLoadId = 41L, destinationLocationId = 10L))
            .`when`().post("/api/v1/transport-orders")
            .then().statusCode(403)
    }

    @Test
    fun `unauthenticated request gets 401`() {
        given().`when`().get("/api/v1/transport-orders").then().statusCode(401)
    }
}

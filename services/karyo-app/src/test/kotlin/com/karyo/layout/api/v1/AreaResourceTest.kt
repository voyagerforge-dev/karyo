package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PageMetadata
import com.karyo.layout.dto.AreaResponse
import com.karyo.layout.dto.CreateAreaRequest
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.service.AreaService
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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class AreaResourceTest {

    @InjectMock
    lateinit var areaService: AreaService

    private fun sampleArea() = AreaResponse(
        id = 1L, name = "Storage Area",
        usages = listOf("STORAGE", "PICKING"),
        created = "2026-03-03T00:00:00Z", modified = "2026-03-03T00:00:00Z",
    )

    // ── 1. Can list areas ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list areas - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleArea()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(areaService).listAllPaginated(anyObj())

        given()
            .`when`().get("/api/v1/areas")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].name", `is`("Storage Area"))
            .body("page.totalElements", `is`(1))
    }

    // ── 2. Can create area ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create area - 201`() {
        doReturn(sampleArea()).`when`(areaService).create(anyObj())

        given()
            .contentType(ContentType.JSON)
            .body(CreateAreaRequest(name = "Storage Area", usages = listOf("STORAGE")))
            .`when`().post("/api/v1/areas")
            .then()
            .statusCode(201)
            .body("name", `is`("Storage Area"))
    }

    // ── 3. Invalid usage returns 400 ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `invalid usage returns 400`() {
        doThrow(LayoutException.InvalidUsage("INVALID"))
            .`when`(areaService).create(anyObj())

        given()
            .contentType(ContentType.JSON)
            .body(CreateAreaRequest(name = "Bad Area", usages = listOf("INVALID")))
            .`when`().post("/api/v1/areas")
            .then()
            .statusCode(400)
    }
}

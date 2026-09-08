package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PageMetadata
import com.karyo.layout.dto.CreateZoneRequest
import com.karyo.layout.dto.ZoneResponse
import com.karyo.layout.service.ZoneService
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

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class ZoneResourceTest {

    @InjectMock
    lateinit var zoneService: ZoneService

    private fun sampleZone() = ZoneResponse(
        id = 1L, name = "Zone A", description = "Main zone",
        overflowZoneId = null,
        created = "2026-03-03T00:00:00Z", modified = "2026-03-03T00:00:00Z",
    )

    // ── 1. Can list zones ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list zones - 200`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleZone()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(zoneService).listAllPaginated(anyObj())

        given()
            .`when`().get("/api/v1/zones")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].name", `is`("Zone A"))
            .body("page.totalElements", `is`(1))
    }

    // ── 2. Can create zone ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create zone - 201`() {
        doReturn(sampleZone()).`when`(zoneService).create(anyObj())

        given()
            .contentType(ContentType.JSON)
            .body(CreateZoneRequest(name = "Zone A"))
            .`when`().post("/api/v1/zones")
            .then()
            .statusCode(201)
            .body("name", `is`("Zone A"))
    }

    // ── 3. Read-only user cannot create zone ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `read-only user cannot create zone - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateZoneRequest(name = "Zone B"))
            .`when`().post("/api/v1/zones")
            .then()
            .statusCode(403)
    }
}

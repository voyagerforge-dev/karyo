package com.karyo.layout.api.v1

import com.karyo.common.pagination.PageMetadata
import com.karyo.common.pagination.PaginatedResponse
import com.karyo.layout.dto.CreateLocationTypeRequest
import com.karyo.layout.dto.LocationTypeResponse
import com.karyo.layout.service.LocationTypeService
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
import java.math.BigDecimal

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class LocationTypeResourceTest {

    @InjectMock
    lateinit var locationTypeService: LocationTypeService

    private fun sampleLocationType() = LocationTypeResponse(
        id = 1L, name = "Pallet Rack",
        height = BigDecimal("2.5"), width = BigDecimal("1.2"),
        depth = BigDecimal("1.0"), liftingCapacity = BigDecimal("1000"),
        fieldLiftingCapacity = BigDecimal("5000"), sectionLiftingCapacity = BigDecimal("20000"),
        created = "2026-03-03T00:00:00Z", modified = "2026-03-03T00:00:00Z",
    )

    // ── 1. Can list location types ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list location types - 200 with paginated envelope`() {
        val paginated = PaginatedResponse(
            content = listOf(sampleLocationType()),
            page = PageMetadata(number = 0, size = 20, totalElements = 1, totalPages = 1),
        )
        doReturn(paginated).`when`(locationTypeService).listAllPaginated(anyObj())

        given()
            .`when`().get("/api/v1/location-types")
            .then()
            .statusCode(200)
            .body("content", hasSize<Any>(1))
            .body("content[0].name", `is`("Pallet Rack"))
            .body("page.totalElements", `is`(1))
    }

    // ── 2. Can create location type ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create location type - 201`() {
        doReturn(sampleLocationType()).`when`(locationTypeService).create(anyObj())

        given()
            .contentType(ContentType.JSON)
            .body(CreateLocationTypeRequest(name = "Pallet Rack", liftingCapacity = BigDecimal("1000")))
            .`when`().post("/api/v1/location-types")
            .then()
            .statusCode(201)
            .body("name", `is`("Pallet Rack"))
    }

    // ── 3. Can update location type (L4: PUT round-trip incl. group lifting capacities) ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can update location type - 200 round-trips field and section lifting capacities`() {
        val updated = sampleLocationType().copy(
            liftingCapacity = BigDecimal("1500"),
            fieldLiftingCapacity = BigDecimal("6000"),
            sectionLiftingCapacity = BigDecimal("25000"),
        )
        doReturn(updated).`when`(locationTypeService).update(ArgumentMatchers.eq(1L), anyObj())

        given()
            .contentType(ContentType.JSON)
            .body(
                CreateLocationTypeRequest(
                    name = "Pallet Rack",
                    liftingCapacity = BigDecimal("1500"),
                    fieldLiftingCapacity = BigDecimal("6000"),
                    sectionLiftingCapacity = BigDecimal("25000"),
                )
            )
            .`when`().put("/api/v1/location-types/1")
            .then()
            .statusCode(200)
            .body("liftingCapacity", `is`(1500))
            .body("fieldLiftingCapacity", `is`(6000))
            .body("sectionLiftingCapacity", `is`(25000))
    }
}

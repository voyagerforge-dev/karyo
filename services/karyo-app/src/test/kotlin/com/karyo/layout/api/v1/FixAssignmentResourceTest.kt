package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateFixAssignmentRequest
import com.karyo.layout.dto.FixAssignmentResponse
import com.karyo.layout.dto.UpdateFixAssignmentRequest
import com.karyo.layout.service.FixAssignmentService
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import java.math.BigDecimal

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T
private fun anyLong(): Long = ArgumentMatchers.anyLong()

@QuarkusTest
class FixAssignmentResourceTest {

    @InjectMock
    lateinit var fixAssignmentService: FixAssignmentService

    private fun sampleAssignment() = FixAssignmentResponse(
        id = 1L, locationId = 1L, locationName = "A-01-01",
        itemDataId = 42L, itemDataNumber = "SKU-042",
        minAmount = BigDecimal("10"), maxAmount = BigDecimal("100"),
        desiredAmount = BigDecimal("50"), maxPickAmount = BigDecimal("15"),
        currentStockAmount = BigDecimal("25"),
        orderIndex = 0,
        created = "2026-03-03T00:00:00Z", modified = "2026-03-03T00:00:00Z",
    )

    // ── 1. Can create fix assignment ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create fix assignment - 201`() {
        doReturn(sampleAssignment()).`when`(fixAssignmentService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L))
            .`when`().post("/api/v1/fix-assignments")
            .then()
            .statusCode(201)
            .body("itemDataNumber", `is`("SKU-042"))
            .body("locationName", `is`("A-01-01"))
    }

    // ── L7: maxPickAmount DTO round-trip + validation ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create fix assignment round-trips maxPickAmount - 201`() {
        doReturn(sampleAssignment()).`when`(fixAssignmentService).create(anyObj(), anyLong())

        given()
            .contentType(ContentType.JSON)
            .body(CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L, maxPickAmount = BigDecimal("15")))
            .`when`().post("/api/v1/fix-assignments")
            .then()
            .statusCode(201)
            .body("maxPickAmount", `is`(15))
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create fix assignment rejects zero maxPickAmount - 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L, maxPickAmount = BigDecimal.ZERO))
            .`when`().post("/api/v1/fix-assignments")
            .then()
            .statusCode(400)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update fix assignment rejects negative maxPickAmount - 400`() {
        given()
            .contentType(ContentType.JSON)
            .body(UpdateFixAssignmentRequest(maxPickAmount = BigDecimal("-1")))
            .`when`().put("/api/v1/fix-assignments/1")
            .then()
            .statusCode(400)
    }

    // ── 2. Can list assignments by location ──

    @Test
    @TestSecurity(user = "viewer", roles = ["layout-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list assignments by location - 200`() {
        doReturn(listOf(sampleAssignment())).`when`(fixAssignmentService).findByLocation(1L, 1L)

        given()
            .`when`().get("/api/v1/fix-assignments?locationId=1")
            .then()
            .statusCode(200)
            .body("[0].itemDataId", `is`(42))
    }

    // ── 3. Can delete assignment ──

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can delete assignment - 204`() {
        given()
            .`when`().delete("/api/v1/fix-assignments/1")
            .then()
            .statusCode(204)
    }
}

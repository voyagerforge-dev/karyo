package com.karyo.product.api.v1

import com.karyo.product.dto.CreateItemUnitRequest
import com.karyo.product.dto.ItemUnitResponse
import com.karyo.product.service.ItemUnitService
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

@QuarkusTest
class ItemUnitResourceTest {

    @InjectMock
    lateinit var itemUnitService: ItemUnitService

    private fun sampleUnits() = listOf(
        ItemUnitResponse(id = 1L, name = "PCS", unitType = "PIECE"),
        ItemUnitResponse(id = 2L, name = "KG", unitType = "WEIGHT"),
        ItemUnitResponse(id = 3L, name = "L", unitType = "VOLUME"),
    )

    // ── 1. Can list item units ───────────────────────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can list item units - 200`() {
        doReturn(sampleUnits()).`when`(itemUnitService).listAll()

        given()
            .`when`().get("/api/v1/item-units")
            .then()
            .statusCode(200)
            .body("$", hasSize<Any>(3))
            .body("[0].name", `is`("PCS"))
    }

    // ── 2. Can create item unit ──────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["product-read", "product-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `can create item unit - 201`() {
        val response = ItemUnitResponse(id = 10L, name = "EA", unitType = "PIECE")
        doReturn(response).`when`(itemUnitService).createUnit(
            org.mockito.ArgumentMatchers.any<CreateItemUnitRequest>() ?: CreateItemUnitRequest(name = "EA")
        )

        given()
            .contentType(ContentType.JSON)
            .body(CreateItemUnitRequest(name = "EA", unitType = "PIECE"))
            .`when`().post("/api/v1/item-units")
            .then()
            .statusCode(201)
            .body("name", `is`("EA"))
    }

    // ── 3. Read-only user cannot create item unit ────────────────────────

    @Test
    @TestSecurity(user = "viewer", roles = ["product-read", "VIEWER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `read-only user cannot create item unit - 403`() {
        given()
            .contentType(ContentType.JSON)
            .body(CreateItemUnitRequest(name = "EA"))
            .`when`().post("/api/v1/item-units")
            .then()
            .statusCode(403)
    }

    // ── 4. Unauthenticated request rejected ──────────────────────────────

    @Test
    fun `unauthenticated request gets 401`() {
        given()
            .`when`().get("/api/v1/item-units")
            .then()
            .statusCode(401)
    }
}

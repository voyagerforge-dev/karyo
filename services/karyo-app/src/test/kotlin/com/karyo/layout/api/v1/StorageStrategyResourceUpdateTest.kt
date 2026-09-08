package com.karyo.layout.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

@QuarkusTest
class StorageStrategyResourceUpdateTest {

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `PUT updates a storage strategy`() {
        val id = given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "SS-EDIT", "mixItem" to true, "mixClient" to false, "nearPickingLocation" to false))
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "SS-EDIT", "mixItem" to false, "mixClient" to true, "nearPickingLocation" to true))
            .`when`().put("/api/v1/storage-strategies/$id")
            .then()
            .statusCode(200)
            .body("mixItem", `is`(false))
            .body("mixClient", `is`(true))
            .body("nearPickingLocation", `is`(true))
    }

    // ── Task 5: L6 sorts — real save-time validation (StorageStrategySortParser) ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create rejects an unknown sorts token - 400 listing valid values, never persists`() {
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "SS-SORTS-BAD-${System.nanoTime()}", "sorts" to "CLIENT,NOT_A_REAL_SORT"))
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(400)
            .body("detail", org.hamcrest.Matchers.containsString("NOT_A_REAL_SORT"))
            .body("detail", org.hamcrest.Matchers.containsString("CAPACITY"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create accepts a valid ordered sorts list and round-trips it verbatim`() {
        given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "SS-SORTS-OK-${System.nanoTime()}", "sorts" to "CAPACITY,ZONE,NAME"))
            .`when`().post("/api/v1/storage-strategies")
            .then()
            .statusCode(201)
            .body("sorts", `is`("CAPACITY,ZONE,NAME"))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `update rejects an unknown sorts token - existing row untouched`() {
        val id = given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "SS-SORTS-UPD-${System.nanoTime()}", "sorts" to "NAME"))
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        given()
            .contentType(ContentType.JSON)
            .body(mapOf("name" to "SS-SORTS-UPD-RENAMED", "sorts" to "not-a-real-sort"))
            .`when`().put("/api/v1/storage-strategies/$id")
            .then()
            .statusCode(400)

        given()
            .`when`().get("/api/v1/storage-strategies/$id")
            .then()
            .statusCode(200)
            .body("sorts", `is`("NAME"))
    }
}

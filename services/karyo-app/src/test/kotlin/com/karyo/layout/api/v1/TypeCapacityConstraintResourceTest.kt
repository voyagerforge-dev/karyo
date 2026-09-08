package com.karyo.layout.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Real round-trip coverage for `/api/v1/type-capacity-constraints` (L2, locations-layout
 * sprint Task 4) — no mocks, mirrors `StorageAreaResourceTest`'s seeding-via-REST style.
 * `TypeCapacityConstraint` is a `BaseEntity` config entity (like `LocationType`/`StorageArea`)
 * so no tenant scoping is exercised here. `unitLoadTypeId` is a FOREIGN MODULE id (inventory)
 * with no lookup SPI available (see the entity's KDoc) — accepted unvalidated, so any long
 * value (here, `System.nanoTime()`-derived) round-trips without a validity check.
 */
@QuarkusTest
class TypeCapacityConstraintResourceTest {

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun ns() = System.nanoTime()

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create, read, update and delete a type capacity constraint round trips`() {
        val s = ns()
        val locationTypeId = createLocationType("TCC-LT-$s")

        val id = given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":$s,"allocation":50,"orderIndex":3}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(201)
            .body("locationTypeId", `is`(locationTypeId.toInt()))
            .body("locationTypeName", `is`("TCC-LT-$s"))
            .body("orderIndex", `is`(3))
            .extract().jsonPath()

        assertThat(id.getLong("unitLoadTypeId")).isEqualTo(s)
        assertThat(id.getDouble("allocation")).isEqualTo(50.0)
        val constraintId = id.getLong("id")

        val get = given()
            .`when`().get("/api/v1/type-capacity-constraints/$constraintId")
            .then().statusCode(200)
            .extract().jsonPath()
        assertThat(get.getDouble("allocation")).isEqualTo(50.0)

        given()
            .`when`().get("/api/v1/type-capacity-constraints?locationTypeId=$locationTypeId")
            .then().statusCode(200)
            .body("size()", `is`(1))
            .body("[0].id", `is`(constraintId.toInt()))

        val updated = given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":$s,"allocation":75,"orderIndex":5}""")
            .`when`().put("/api/v1/type-capacity-constraints/$constraintId")
            .then().statusCode(200)
            .body("orderIndex", `is`(5))
            .extract().jsonPath()
        assertThat(updated.getDouble("allocation")).isEqualTo(75.0)

        given()
            .`when`().delete("/api/v1/type-capacity-constraints/$constraintId")
            .then().statusCode(204)

        given()
            .`when`().get("/api/v1/type-capacity-constraints/$constraintId")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with duplicate location-type unit-load-type pair returns 409`() {
        val s = ns()
        val locationTypeId = createLocationType("TCC-DUP-$s")

        given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":$s,"allocation":100}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(201)

        given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":$s,"allocation":80}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with unknown location type returns 422`() {
        val s = ns()
        given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$s,"unitLoadTypeId":$s}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(422)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with out-of-bounds allocation returns 400`() {
        val s = ns()
        val locationTypeId = createLocationType("TCC-BOUNDS-$s")

        given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":$s,"allocation":0}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(400)

        given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":${s + 1},"allocation":1000}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(400)
    }

    /** Documents that unitLoadTypeId is accepted unvalidated — see the entity's KDoc. */
    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with a made-up unit load type id succeeds - unvalidated by design`() {
        val s = ns()
        val locationTypeId = createLocationType("TCC-ULT-$s")

        given().contentType(ContentType.JSON)
            .body("""{"locationTypeId":$locationTypeId,"unitLoadTypeId":$s,"allocation":${BigDecimal("100")}}""")
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(201)
    }
}

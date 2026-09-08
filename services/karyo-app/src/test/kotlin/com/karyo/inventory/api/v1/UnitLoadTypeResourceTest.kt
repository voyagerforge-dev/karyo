package com.karyo.inventory.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.CoreMatchers.*
import org.junit.jupiter.api.Test

@QuarkusTest
class UnitLoadTypeResourceTest {

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `create with duplicate name returns 409 problem detail`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"ULT-DUP-001"}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201)

        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"ULT-DUP-001"}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(409)
            .body("type", `is`("https://karyo.com/errors/duplicate-name"))
            .body("detail", containsString("ULT-DUP-001"))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `create persists dimension fields`() {
        val id = given()
            .contentType(ContentType.JSON)
            .body(
                """{"name":"ULT-DIM-001","height":150,"width":120,"depth":100,
                   "weight":25,"liftingCapacity":1500,"usages":"STORAGE","aggregateStocks":true}""",
            )
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201)
            .body("name", `is`("ULT-DIM-001"))
            .extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/unit-load-types/$id")
            .then().statusCode(200)
            .body("height", `is`(150.0f))
            .body("width", `is`(120.0f))
            .body("depth", `is`(100.0f))
            .body("weight", `is`(25.0f))
            .body("liftingCapacity", `is`(1500.0f))
            .body("usages", `is`("STORAGE"))
            .body("aggregateStocks", `is`(true))
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `create and delete invalidate the unit-load-types list cache`() {
        // Prime the cache
        given().`when`().get("/api/v1/unit-load-types")
            .then().statusCode(200)
            .body("name", not(hasItem("ULT-CACHE-001")))

        // Create must invalidate -- new entry visible immediately
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"name":"ULT-CACHE-001"}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        given().`when`().get("/api/v1/unit-load-types")
            .then().statusCode(200)
            .body("name", hasItem("ULT-CACHE-001"))

        // Delete must invalidate -- entry gone immediately
        given().`when`().delete("/api/v1/unit-load-types/$id")
            .then().statusCode(204)

        given().`when`().get("/api/v1/unit-load-types")
            .then().statusCode(200)
            .body("name", not(hasItem("ULT-CACHE-001")))
    }

    @Test
    @TestSecurity(user = "viewer", roles = ["inventory-read"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `viewer cannot create unit load type`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"ULT-DENIED"}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `updating a unit load type changes its fields and is visible through the cached list`() {
        val name = "ULT-UPD-${System.nanoTime()}"
        val id = given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$name","height":100,"manageEmpties":false}""")
            .`when`().post("/api/v1/unit-load-types")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

        // Prime the cache and confirm the pre-update shape.
        val before = given().`when`().get("/api/v1/unit-load-types")
            .then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("")
        val beforeEntry = before.first { (it["id"] as Number).toLong() == id }
        assertThat(beforeEntry["name"]).isEqualTo(name)
        assertThat(beforeEntry["manageEmpties"]).isEqualTo(false)

        val newName = "$name-NEW"
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"$newName","height":200,"manageEmpties":true}""")
            .`when`().put("/api/v1/unit-load-types/$id")
            .then().statusCode(200)
            .body("name", `is`(newName))
            .body("manageEmpties", `is`(true))

        // Must be visible through the CACHED list read (findAll is @CacheResult), proving
        // update carries @CacheInvalidateAll like create and delete already do.
        val after = given().`when`().get("/api/v1/unit-load-types")
            .then().statusCode(200)
            .extract().jsonPath().getList<Map<String, Any>>("")
        val afterEntry = after.first { (it["id"] as Number).toLong() == id }
        assertThat(afterEntry["name"]).isEqualTo(newName)
        assertThat(afterEntry["manageEmpties"]).isEqualTo(true)
        assertThat((afterEntry["height"] as Number).toDouble()).isEqualTo(200.0)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1")])
    fun `updating an unknown unit load type is 404`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"name":"ULT-UPD-MISSING"}""")
            .`when`().put("/api/v1/unit-load-types/99999999")
            .then().statusCode(404)
    }
}

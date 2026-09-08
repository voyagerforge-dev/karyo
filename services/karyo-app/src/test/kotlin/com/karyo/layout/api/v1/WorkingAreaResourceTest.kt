package com.karyo.layout.api.v1

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test

/**
 * Real round-trip coverage for `/api/v1/working-areas` (L5, locations-layout sprint Task 8)
 * — no mocks, mirrors `StorageAreaResourceTest`'s style exactly (same M2M-over-clusters
 * shape). `WorkingArea` is a `BaseEntity` config entity so no tenant scoping is exercised
 * here — see `com.karyo.layout.WorkingAreaLookupTest` for the SPI-level resolution coverage.
 */
@QuarkusTest
class WorkingAreaResourceTest {

    private fun createCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun ns() = System.nanoTime()

    // ── 1. CRUD round trip: create with clusters, read back, update, delete ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create, read, update and delete a working area round trips`() {
        val s = ns()
        val clusterA = createCluster("WA-CL-A-$s")
        val clusterB = createCluster("WA-CL-B-$s")

        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"WA-$s","clusterIds":[$clusterA]}""")
            .`when`().post("/api/v1/working-areas")
            .then().statusCode(201)
            .body("name", `is`("WA-$s"))
            .body("clusterIds", hasSize<Any>(1))
            .extract().jsonPath().getLong("id")

        given()
            .`when`().get("/api/v1/working-areas/$areaId")
            .then().statusCode(200)
            .body("name", `is`("WA-$s"))
            .body("clusterIds[0]", `is`(clusterA.toInt()))

        // Update: rename + replace the cluster set with both clusters
        given().contentType(ContentType.JSON)
            .body("""{"name":"WA-$s-RENAMED","clusterIds":[$clusterA,$clusterB]}""")
            .`when`().put("/api/v1/working-areas/$areaId")
            .then().statusCode(200)
            .body("name", `is`("WA-$s-RENAMED"))
            .body("clusterIds", containsInAnyOrder(clusterA.toInt(), clusterB.toInt()))

        // Delete — WorkingArea has no dependents concept, plain delete always succeeds
        given()
            .`when`().delete("/api/v1/working-areas/$areaId")
            .then().statusCode(204)

        given()
            .`when`().get("/api/v1/working-areas/$areaId")
            .then().statusCode(404)
    }

    // ── 2. Unknown cluster id in clusterIds -> 400 ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with unknown cluster id returns 400`() {
        val s = ns()
        val unknownClusterId = s // guaranteed not to exist as a real id

        given().contentType(ContentType.JSON)
            .body("""{"name":"WA-BADCLUSTER-$s","clusterIds":[$unknownClusterId]}""")
            .`when`().post("/api/v1/working-areas")
            .then().statusCode(400)
    }

    // ── 3. Duplicate name -> 409 ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `create with duplicate name returns 409`() {
        val s = ns()
        given().contentType(ContentType.JSON)
            .body("""{"name":"WA-DUP-$s"}""")
            .`when`().post("/api/v1/working-areas")
            .then().statusCode(201)

        given().contentType(ContentType.JSON)
            .body("""{"name":"WA-DUP-$s"}""")
            .`when`().post("/api/v1/working-areas")
            .then().statusCode(409)
    }
}

package com.karyo.layout

import com.karyo.layout.spi.ClearingLocationLookup
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A2-1: real DB-backed coverage for the `is_clearing` singleton — the partial unique
 * index (V310) fires at flush/commit for [com.karyo.layout.service.LocationService.updateLocation]
 * (existing entity, deferred until an explicit flush) and immediately inside
 * [com.karyo.layout.service.LocationService.createLocation] itself (IDENTITY generation means
 * `persist()` issues the INSERT right away, Task 10) — these tests exercise the actual
 * PersistenceException-to-409 mapping for both paths against a real Postgres, not a mocked
 * service (see [com.karyo.layout.api.v1.LocationResourceTest] for the shallow REST
 * pass-through check). Every test cleans up after itself (always ends with isClearing unset)
 * so no method leaves a lingering singleton for another test.
 */
@QuarkusTest
class ClearingLocationTest {

    @Inject
    lateinit var lookup: ClearingLocationLookup

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun setClearing(id: Long, value: Boolean) =
        given().contentType(ContentType.JSON)
            .body("""{"isClearing":$value}""")
            .`when`().put("/api/v1/locations/$id")

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `updateLocation sets, clears, and leaves isClearing unchanged when omitted`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("ClrType-$s")
        val areaId = createArea("ClrArea-$s")
        val locId = createLocation("CLR-A-$s", ltId, areaId)

        // set
        setClearing(locId, true).then().statusCode(200).body("isClearing", org.hamcrest.CoreMatchers.`is`(true))

        // omitted -> unchanged (send an unrelated field only)
        given().contentType(ContentType.JSON)
            .body("""{"rack":"R1"}""")
            .`when`().put("/api/v1/locations/$locId")
            .then().statusCode(200).body("isClearing", org.hamcrest.CoreMatchers.`is`(true))
            .body("rack", org.hamcrest.CoreMatchers.`is`("R1"))

        // clear
        setClearing(locId, false).then().statusCode(200).body("isClearing", org.hamcrest.CoreMatchers.`is`(false))
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `setting isClearing on a second location surfaces a clean 409, not a raw 500`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("ClrType2-$s")
        val areaId = createArea("ClrArea2-$s")
        val locA = createLocation("CLR-B-$s", ltId, areaId)
        val locB = createLocation("CLR-C-$s", ltId, areaId)

        setClearing(locA, true).then().statusCode(200)

        // The partial unique index only fires at flush -- this must surface as a clean
        // 4xx (409 preferred) with a ProblemDetail body, never a raw 500.
        setClearing(locB, true)
            .then()
            .statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.notNullValue())
            .body("title", org.hamcrest.CoreMatchers.notNullValue())
            .body("status", org.hamcrest.CoreMatchers.`is`(409))
            .body("detail", org.hamcrest.CoreMatchers.notNullValue())

        // locB must NOT have been left half-written by the failed flush.
        given().`when`().get("/api/v1/locations/$locB")
            .then().statusCode(200).body("isClearing", org.hamcrest.CoreMatchers.`is`(false))

        // cleanup -- don't leave a lingering singleton for other tests.
        setClearing(locA, false).then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `creating a location with isClearing=true when another already holds it surfaces a clean 409 and persists no row`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("ClrType5-$s")
        val areaId = createArea("ClrArea5-$s")

        // Location A: isClearing set directly at create time (Task 10 -- CreateLocationRequest
        // previously had no isClearing field at all).
        val locAName = "CLR-E-$s"
        val locA = given().contentType(ContentType.JSON)
            .body("""{"name":"$locAName","locationTypeId":$ltId,"areaId":$areaId,"isClearing":true}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).body("isClearing", org.hamcrest.CoreMatchers.`is`(true))
            .extract().jsonPath().getLong("id")

        // Location B: same attempt -- IDENTITY generation means persist() issues the INSERT
        // immediately, so the V310 partial unique index fires inside createLocation() itself,
        // not at a later flush. Must surface as a clean 409, never a raw 500.
        val locBName = "CLR-F-$s"
        given().contentType(ContentType.JSON)
            .body("""{"name":"$locBName","locationTypeId":$ltId,"areaId":$areaId,"isClearing":true}""")
            .`when`().post("/api/v1/locations")
            .then()
            .statusCode(409)
            .body("type", org.hamcrest.CoreMatchers.notNullValue())
            .body("title", org.hamcrest.CoreMatchers.notNullValue())
            .body("status", org.hamcrest.CoreMatchers.`is`(409))
            .body("detail", org.hamcrest.CoreMatchers.notNullValue())

        // Location B must NOT have been persisted at all -- its scan code (defaults to name)
        // 404s, proving the failed insert left no row behind.
        given().`when`().get("/api/v1/locations/by-scan-code/$locBName")
            .then().statusCode(404)

        // cleanup -- don't leave a lingering singleton for other tests.
        setClearing(locA, false).then().statusCode(200)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `ClearingLocationLookup resolves the configured singleton and is unscoped by client`() {
        val s = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("ClrType3-$s")
        val areaId = createArea("ClrArea3-$s")
        val locId = createLocation("CLR-D-$s", ltId, areaId)

        setClearing(locId, true).then().statusCode(200)

        val found = lookup.findClearing()
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(locId)
        assertThat(found.name).isEqualTo("CLR-D-$s")

        // cleanup
        setClearing(locId, false).then().statusCode(200)
        assertThat(lookup.findClearing()).isNull()
    }
}

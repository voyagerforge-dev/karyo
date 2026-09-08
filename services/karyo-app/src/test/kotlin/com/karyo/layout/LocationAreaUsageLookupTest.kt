package com.karyo.layout

import com.karyo.layout.spi.LocationAreaUsageLookup
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
 * Integration test for [LocationAreaUsageLookup] — real beans, no mocks.
 *
 * Seeding pattern mirrors [FixAssignmentLookupTest]: location-type → area (with an explicit
 * `usages` list) → location.
 */
@QuarkusTest
class LocationAreaUsageLookupTest {

    @Inject
    lateinit var lookup: LocationAreaUsageLookup

    // ── Seeding helpers ────────────────────────────────────────────────────

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String, usages: List<String>): Long {
        val usagesJson = usages.joinToString(",") { "\"$it\"" }
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":[$usagesJson]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8001"), Claim(key = "tenant_code", value = "ACME")])
    fun `location in a PICKING-usage area is returned`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LAU-$ns")
        val pickingAreaId = createArea("AREA-LAU-PICK-$ns", listOf("PICKING"))
        val pickingLocId = createLocation("LOC-LAU-PICK-$ns", ltId, pickingAreaId)

        val result = lookup.pickingLocationIds(8001L)

        assertThat(result).contains(pickingLocId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8002"), Claim(key = "tenant_code", value = "ACME")])
    fun `location in a STORAGE-only area is not returned`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LAU-ST-$ns")
        val storageAreaId = createArea("AREA-LAU-ST-$ns", listOf("STORAGE"))
        val storageLocId = createLocation("LOC-LAU-ST-$ns", ltId, storageAreaId)

        val result = lookup.pickingLocationIds(8002L)

        assertThat(result).doesNotContain(storageLocId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8003"), Claim(key = "tenant_code", value = "ACME")])
    fun `tenant scope -- a different clientId does not see this tenant's picking location`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-LAU-TEN-$ns")
        val pickingAreaId = createArea("AREA-LAU-TEN-$ns", listOf("PICKING"))
        val pickingLocId = createLocation("LOC-LAU-TEN-$ns", ltId, pickingAreaId)

        val ownTenant = lookup.pickingLocationIds(8003L)
        val otherTenant = lookup.pickingLocationIds(8004L)

        assertThat(ownTenant).contains(pickingLocId)
        assertThat(otherTenant).doesNotContain(pickingLocId)
    }
}

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
 * Integration test for [LocationAreaUsageLookup.crossDockStagingLocationIds] -- real beans, no
 * mocks. Mirrors [LocationAreaUsageLookupTest]'s seeding pattern (location-type -> area with an
 * explicit `usages` list -> location).
 */
@QuarkusTest
class CrossDockStagingLookupTest {

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
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8101"), Claim(key = "tenant_code", value = "ACME")])
    fun `location in a CROSS_DOCK_STAGING-usage area is returned`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-CDS-$ns")
        val stagingAreaId = createArea("AREA-CDS-STAGE-$ns", listOf("CROSS_DOCK_STAGING"))
        val stagingLocId = createLocation("LOC-CDS-STAGE-$ns", ltId, stagingAreaId)

        val result = lookup.crossDockStagingLocationIds(8101L)

        assertThat(result).contains(stagingLocId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8102"), Claim(key = "tenant_code", value = "ACME")])
    fun `location in a PICKING-only area is not returned`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-CDS-PICK-$ns")
        val pickingAreaId = createArea("AREA-CDS-PICK-$ns", listOf("PICKING"))
        val pickingLocId = createLocation("LOC-CDS-PICK-$ns", ltId, pickingAreaId)

        val result = lookup.crossDockStagingLocationIds(8102L)

        assertThat(result).doesNotContain(pickingLocId)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8103"), Claim(key = "tenant_code", value = "ACME")])
    fun `tenant scope -- a different clientId does not see this tenant's staging location`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = createLocationType("LT-CDS-TEN-$ns")
        val stagingAreaId = createArea("AREA-CDS-TEN-$ns", listOf("CROSS_DOCK_STAGING"))
        val stagingLocId = createLocation("LOC-CDS-TEN-$ns", ltId, stagingAreaId)

        val ownTenant = lookup.crossDockStagingLocationIds(8103L)
        val otherTenant = lookup.crossDockStagingLocationIds(8104L)

        assertThat(ownTenant).contains(stagingLocId)
        assertThat(otherTenant).doesNotContain(stagingLocId)
    }
}

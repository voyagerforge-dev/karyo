package com.karyo.layout.service

import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test (REAL layout beans + DB) for the locations-layout sprint's Task 6:
 * the [LocationFinderService] `allocationState` gate in
 * [com.karyo.layout.repository.StorageLocationRepository.findPutawayCandidates] — myWMS's
 * operator "mark full/blocked" flag, excluded from auto-search regardless of allocation/lock.
 *
 * A sibling of [LocationFinderTest]/[LocationFinderCapacityTest] (not an addition to either) —
 * kept as its own file per the sprint's established large-class split, duplicating a handful
 * of small REST-seeding helpers rather than sharing them.
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy), same as [LocationFinderTest].
 */
@QuarkusTest
class LocationFinderAllocationStateTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var em: jakarta.persistence.EntityManager

    // ── REST seeding helpers ─────────────────────────────────────────────

    private fun createArea(name: String, usages: List<String>): Long {
        val usagesJson = usages.joinToString(",") { "\"$it\"" }
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":[$usagesJson]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createZone(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/zones")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId,"zoneId":$zoneId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Directly set allocationState (no REST setter yet — admin-only per the L3 design). */
    @Transactional
    fun setAllocationState(id: Long, state: Int) {
        val loc = em.find(com.karyo.layout.domain.model.StorageLocation::class.java, id)
        loc.allocationState = state
    }

    private fun request(reservationKey: Long, preferredZoneId: Long?) = LocationFinderRequest(
        unitLoadId = 9500L + reservationKey,
        unitLoadTypeId = null,
        weight = BigDecimal.ZERO,
        clientId = 1L,
        reservationKey = reservationKey,
        preferredZoneId = preferredZoneId,
    )

    private fun suffix() = System.nanoTime()

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a location with allocationState 0 (default) is found - regression pin`() {
        val s = suffix()
        val storage = createArea("AS-DEFAULT-$s", listOf("STORAGE"))
        val zone = createZone("AS-Z-DEFAULT-$s")
        val type = createLocationType("AS-LT-DEFAULT-$s")
        val loc = createLocation("AS-L-DEFAULT-$s", type, storage, zone)

        // allocationState left at its migrated default (0) — never touched.
        val result = locationFinder.findPutawayLocation(request(s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(loc)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a location marked allocationState 1 is excluded even though it is empty and unlocked`() {
        val s = suffix()
        val storage = createArea("AS-MARKED-$s", listOf("STORAGE"))
        val zone = createZone("AS-Z-MARKED-$s")
        val type = createLocationType("AS-LT-MARKED-$s")
        val loc = createLocation("AS-L-MARKED-$s", type, storage, zone)
        setAllocationState(loc, 1)

        val result = locationFinder.findPutawayLocation(request(s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an allocationState-marked location is skipped in favor of a normal sibling`() {
        val s = suffix()
        val storage = createArea("AS-MIX-$s", listOf("STORAGE"))
        val zone = createZone("AS-Z-MIX-$s")
        val type = createLocationType("AS-LT-MIX-$s")

        val marked = createLocation("AS-L-MARKED2-$s", type, storage, zone)
        setAllocationState(marked, 2)
        val normal = createLocation("AS-L-NORMAL-$s", type, storage, zone)

        val result = locationFinder.findPutawayLocation(request(s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(normal)
    }
}

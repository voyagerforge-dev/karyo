package com.karyo.layout.service

import com.karyo.layout.repository.LocationReservationRepository
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
 * Integration test (REAL layout beans + DB) for the locations-layout sprint's Task 4: the
 * [LocationFinderService] `TypeCapacityConstraint` filter 7 (compatibility + capacity),
 * which replaces the old permissive UL-type stub.
 *
 * A sibling of [LocationFinderTest]/[LocationFinderAreaTest] (not an addition to either) —
 * kept as its own file per the sprint's established large-class split, duplicating a handful
 * of small REST-seeding helpers rather than sharing them.
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy), same as [LocationFinderTest].
 */
@QuarkusTest
class LocationFinderCapacityTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

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

    /** Directly set base allocation (no REST setter) — mirrors [LocationFinderTest.setAllocation]. */
    @Transactional
    fun setAllocation(id: Long, allocation: BigDecimal) {
        val loc = reservationRepository.getEntityManager()
            .find(com.karyo.layout.domain.model.StorageLocation::class.java, id)
        loc.allocation = allocation
    }

    private fun createConstraint(locationTypeId: Long, unitLoadTypeId: Long, allocation: String, orderIndex: Int = 0): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"locationTypeId":$locationTypeId,"unitLoadTypeId":$unitLoadTypeId,""" +
                    """"allocation":$allocation,"orderIndex":$orderIndex}""",
            )
            .`when`().post("/api/v1/type-capacity-constraints")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun request(reservationKey: Long, unitLoadTypeId: Long?, preferredZoneId: Long?) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = unitLoadTypeId,
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
    fun `empty constraint matrix leaves a location type fully unrestricted - regression pin`() {
        val s = suffix()
        val storage = createArea("TCC-EMPTY-$s", listOf("STORAGE"))
        val zone = createZone("TCC-Z-EMPTY-$s")
        val type = createLocationType("TCC-LT-EMPTY-$s")
        val loc = createLocation("TCC-L-EMPTY-$s", type, storage, zone)

        // No TypeCapacityConstraint rows created for `type` at all.
        val result = locationFinder.findPutawayLocation(request(s, unitLoadTypeId = 42L, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(loc)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `rows exist for the location type but none match the incoming UL type - excluded`() {
        val s = suffix()
        val storage = createArea("TCC-NOMATCH-$s", listOf("STORAGE"))
        val zone = createZone("TCC-Z-NOMATCH-$s")
        val type = createLocationType("TCC-LT-NOMATCH-$s")
        createLocation("TCC-L-NOMATCH-$s", type, storage, zone)

        // Constraint exists, but only for a DIFFERENT unit-load type than the request carries.
        createConstraint(type, unitLoadTypeId = s, allocation = "100")

        val result = locationFinder.findPutawayLocation(request(s, unitLoadTypeId = s + 1, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `matching constraint row - within capacity is eligible, over capacity is excluded`() {
        val s = suffix()
        val storage = createArea("TCC-CAP-$s", listOf("STORAGE"))
        val zone = createZone("TCC-Z-CAP-$s")
        val type = createLocationType("TCC-LT-CAP-$s")
        val ulType = s

        createConstraint(type, unitLoadTypeId = ulType, allocation = "50")

        // 40% allocated + 50% constraint headroom (100-50=50) -> 40 <= 50 -> eligible.
        val underLoc = createLocation("TCC-L-UNDER-$s", type, storage, zone)
        setAllocation(underLoc, BigDecimal("40"))
        val underResult = locationFinder.findPutawayLocation(request(s, unitLoadTypeId = ulType, preferredZoneId = zone))
        assertThat(underResult).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((underResult as LocationFinderResult.Found).locationId).isEqualTo(underLoc)
        locationFinder.releaseReservation(s)

        // A second location at 60% -> 60 > 50 headroom -> excluded; nothing else qualifies.
        val overLoc = createLocation("TCC-L-OVER-$s", type, storage, zone)
        setAllocation(overLoc, BigDecimal("60"))
        setAllocation(underLoc, BigDecimal("60")) // also push the first one over so both are excluded
        val overResult = locationFinder.findPutawayLocation(request(s + 1, unitLoadTypeId = ulType, preferredZoneId = zone))
        assertThat(overResult).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `oversize row excludes a non-empty location but allows a completely empty one`() {
        val s = suffix()
        val storage = createArea("TCC-OVERSIZE-$s", listOf("STORAGE"))
        val zone = createZone("TCC-Z-OVERSIZE-$s")
        val type = createLocationType("TCC-LT-OVERSIZE-$s")
        val ulType = s

        createConstraint(type, unitLoadTypeId = ulType, allocation = "150") // myWMS oversize row

        val nonEmpty = createLocation("TCC-L-NONEMPTY-$s", type, storage, zone)
        setAllocation(nonEmpty, BigDecimal("10"))

        // Only a non-empty candidate exists under the oversize constraint -> excluded -> NoLocation.
        val excludedResult = locationFinder.findPutawayLocation(request(s, unitLoadTypeId = ulType, preferredZoneId = zone))
        assertThat(excludedResult).isInstanceOf(LocationFinderResult.NoLocation::class.java)

        // A second, completely empty location under the same oversize constraint IS eligible.
        val empty = createLocation("TCC-L-EMPTY2-$s", type, storage, zone)
        val allowedResult = locationFinder.findPutawayLocation(request(s + 1, unitLoadTypeId = ulType, preferredZoneId = zone))
        assertThat(allowedResult).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((allowedResult as LocationFinderResult.Found).locationId).isEqualTo(empty)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unconstrained location type is still found even when a sibling type in the same find is constrained out`() {
        val s = suffix()
        val storage = createArea("TCC-MIX-$s", listOf("STORAGE"))
        val zone = createZone("TCC-Z-MIX-$s")
        val ulType = s

        val constrainedType = createLocationType("TCC-LT-CONSTRAINED-$s")
        val freeType = createLocationType("TCC-LT-FREE-$s")

        // The constrained type only accepts a DIFFERENT UL type -> its location is excluded.
        createConstraint(constrainedType, unitLoadTypeId = ulType + 1, allocation = "100")
        createLocation("TCC-L-CONSTRAINED-$s", constrainedType, storage, zone)

        // The free type has NO constraint rows at all -> unrestricted, still eligible.
        val freeLoc = createLocation("TCC-L-FREE-$s", freeType, storage, zone)

        val result = locationFinder.findPutawayLocation(request(s, unitLoadTypeId = ulType, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(freeLoc)
    }
}

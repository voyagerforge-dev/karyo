package com.karyo.layout.service

import com.karyo.layout.domain.model.FixAssignment
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test (REAL layout beans + DB) for the LF8 fixed-assignment exclusion pass: a
 * STORAGE location carrying ANY [FixAssignment] is excluded from general putaway, as specified
 * in `docs/functional/location-finder.md#2-the-built-in-filter-passes--as-implemented`. Stock
 * reaches a fixed location
 * through the replenishment flow instead of the general putaway finder.
 *
 * Seeding pattern mirrors [LocationFinderTest]: each test scopes its own zone so the shared
 * app DB's other STORAGE locations never bleed into the candidate set. client_id is fixed at 1
 * for all seeding/finder calls (silo tenancy).
 */
@QuarkusTest
class LocationFinderFixExclusionTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var fixAssignmentRepository: FixAssignmentRepository

    @Inject
    lateinit var storageLocationRepository: StorageLocationRepository

    private fun suffix() = System.nanoTime()

    // ── REST seeding helpers (mirrors LocationFinderTest) ─────────────────

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

    private fun createLocationType(name: String, liftingCapacity: BigDecimal?): Long {
        val cap = liftingCapacity?.let { ""","liftingCapacity":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$cap}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long? = null): Long {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createFixAssignment(locationId: Long, itemDataId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId}""")
            .`when`().post("/api/v1/fix-assignments")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Item-unit + product creation, mirroring [LocationFinderSortTest.createProductForFix] --
     * a [FixAssignment] created through REST validates its `itemDataId` against a real product.
     * Bounded suffix (`@Size(max = 20)` on item-unit name, same workaround as
     * [FixAssignmentLookupTest]) since a full [System.nanoTime] overflows a short prefix. */
    private fun createProductForFix(s: Long): Long {
        val suffix = s.toString().takeLast(8)
        val itemUnitId = given().contentType(ContentType.JSON)
            .body("""{"name":"FXX-IU-$suffix","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")
        return given().contentType(ContentType.JSON)
            .body("""{"number":"FXX-SKU-$suffix","name":"Fix Exclusion Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun request(
        reservationKey: Long,
        preferredZoneId: Long? = null,
        itemDataId: Long? = null,
    ) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = 1L,
        weight = BigDecimal.ZERO,
        clientId = 1L,
        reservationKey = reservationKey,
        preferredZoneId = preferredZoneId,
        itemDataId = itemDataId,
    )

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a location carrying a fix assignment is excluded from general putaway`() {
        val s = suffix()
        val storage = createArea("FXA-$s", listOf("STORAGE"))
        val zone = createZone("FXZ-$s")
        val type = createLocationType("FXT-$s", BigDecimal("1000"))
        val itemDataId = createProductForFix(s)

        // "A-FIXED" sorts before "B-OPEN" by name -- pre-fix, the finder's plain
        // allocation-ASC/name-ASC order would pick the FIXED one, so this genuinely fails
        // before the exclusion predicate exists (not by accidental naming).
        val fixed = createLocation("FX1-A-FIXED-$s", type, storage, zoneId = zone)
        val open = createLocation("FX1-B-OPEN-$s", type, storage, zoneId = zone)
        createFixAssignment(fixed, itemDataId = itemDataId)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(open)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `the exclusion is unconditional - even a fix assignment for the incoming item excludes`() {
        val s = suffix()
        val storage = createArea("FXB-$s", listOf("STORAGE"))
        val zone = createZone("FXZB-$s")
        val type = createLocationType("FXTB-$s", BigDecimal("1000"))
        val itemDataId = createProductForFix(s)

        val fixed = createLocation("FX2-A-FIXED-$s", type, storage, zoneId = zone)
        val open = createLocation("FX2-B-OPEN-$s", type, storage, zoneId = zone)
        createFixAssignment(fixed, itemDataId = itemDataId)

        // request.itemDataId equals the assignment's own item -- the exclusion still applies,
        // proving the predicate is unconditional (no itemDataId comparison at all).
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, itemDataId = itemDataId),
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(open)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a fix assignment belonging to another client still excludes the location`() {
        val s = suffix()
        val storage = createArea("FXC-$s", listOf("STORAGE"))
        val zone = createZone("FXZC-$s")
        val type = createLocationType("FXTC-$s", BigDecimal("1000"))

        val fixed = createLocation("FX3-A-FIXED-$s", type, storage, zoneId = zone)
        val open = createLocation("FX3-B-OPEN-$s", type, storage, zoneId = zone)

        // Persisted directly (bypassing REST, which is pinned to the @OidcSecurity
        // client_id=1 claim for this whole test method) so the FixAssignment can carry a
        // DIFFERENT clientId -- mirrors FixAssignmentLookupTest's cross-tenant fixture
        // pattern. The exclusion predicate has no clientId in its subquery on purpose.
        QuarkusTransaction.requiringNew().run {
            val location = storageLocationRepository.findById(fixed)!!
            val assignment = FixAssignment().apply {
                this.location = location
                this.itemDataId = 555_003L // arbitrary; direct-persist bypasses product validation, so no real product is needed
                this.clientId = 999L
            }
            fixAssignmentRepository.persist(assignment)
        }

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(open)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `locations without fix assignments are unaffected - regression pin`() {
        val s = suffix()
        val storage = createArea("FXD-$s", listOf("STORAGE"))
        val zone = createZone("FXZD-$s")
        val type = createLocationType("FXTD-$s", BigDecimal("1000"))

        // Both empty, unlocked, no fix assignment anywhere -- name ASC tie-break picks
        // "FX4-A-EMPTY" over "FX4-B-OTHER" exactly as it did before this pass existed.
        val empty = createLocation("FX4-A-EMPTY-$s", type, storage, zoneId = zone)
        createLocation("FX4-B-OTHER-$s", type, storage, zoneId = zone)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(empty)
    }
}

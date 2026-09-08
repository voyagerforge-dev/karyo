package com.karyo.layout.service

import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.security.TenantContext
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
 * Integration test (REAL layout beans + DB) for the locations-layout sprint's Task 7 (L4):
 * [LocationFinderService]'s field/section group lifting-capacity check ([GroupCapacityReader]).
 *
 * A sibling of [LocationFinderTest] (not an addition to it), mirroring [LocationFinderAreaTest]'s
 * split for the same Detekt `LargeClass` reason — duplicates a handful of small REST-seeding
 * helpers rather than sharing them.
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy), same as the sibling files.
 */
@QuarkusTest
class LocationFinderGroupCapacityTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    @Inject
    lateinit var tenantContext: TenantContext

    private val client = 1L

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

    private fun createLocationType(
        name: String,
        fieldLiftingCapacity: BigDecimal? = null,
        sectionLiftingCapacity: BigDecimal? = null,
    ): Long {
        val fields = mutableListOf("\"name\":\"$name\"")
        fieldLiftingCapacity?.let { fields += "\"fieldLiftingCapacity\":$it" }
        sectionLiftingCapacity?.let { fields += "\"sectionLiftingCapacity\":$it" }
        return given().contentType(ContentType.JSON)
            .body("{${fields.joinToString(",")}}")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createLocation(
        name: String,
        typeId: Long,
        areaId: Long,
        zoneId: Long? = null,
        rack: String? = null,
        field: String? = null,
        section: String? = null,
    ): Long {
        val fields = mutableListOf(
            "\"name\":\"$name\"",
            "\"locationTypeId\":$typeId",
            "\"areaId\":$areaId",
        )
        zoneId?.let { fields += "\"zoneId\":$it" }
        rack?.let { fields += "\"rack\":\"$it\"" }
        field?.let { fields += "\"field\":\"$it\"" }
        section?.let { fields += "\"section\":\"$it\"" }
        return given().contentType(ContentType.JSON)
            .body("{${fields.joinToString(",")}}")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Directly set base allocation (no REST setter) — simulates an already-full location,
     * mirrors [LocationFinderTest.setAllocation]. */
    @Transactional
    fun setAllocation(id: Long, allocation: BigDecimal) {
        val loc = reservationRepository.getEntityManager()
            .find(com.karyo.layout.domain.model.StorageLocation::class.java, id)
        loc.allocation = allocation
    }

    /**
     * Direct entity persistence of an ON_STOCK unit load at [locationId] carrying [weight]
     * (nullable — an unweighed unit load, for the degradation-path test). Bypasses REST since
     * [com.karyo.inventory.api.dto.CreateUnitLoadRequest] has no `weight` field — mirrors
     * [LocationFinderAreaTest.seedStock]'s direct-entity pattern. `unitLoadTypeId` 1 is the
     * fixture type every other test in this module already relies on existing.
     */
    @Transactional
    fun seedWeightedStock(locationId: Long, locationName: String, weight: BigDecimal?): Long {
        val em = reservationRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-GRP-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = locationName
            this.clientId = client
            this.weight = weight
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = client
            itemDataId = 1L
            itemDataNumber = "GRP-SKU"
            amount = BigDecimal.ONE
            unitLoad = ul
            state = 300
        }
        em.persist(su)
        return su.id!!
    }

    private fun request(reservationKey: Long, weight: BigDecimal, preferredZoneId: Long?) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = 1L,
        weight = weight,
        clientId = client,
        reservationKey = reservationKey,
        preferredZoneId = preferredZoneId,
    )

    private fun suffix() = System.nanoTime()

    // ── field group cap ──────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `field cap exceeded by neighbor occupancy plus incoming weight excludes the candidate`() {
        val s = suffix()
        val storage = createArea("GCF-ST-$s", listOf("STORAGE"))
        val zone = createZone("GCF-Z-$s")
        val type = createLocationType("GCF-LT-$s", fieldLiftingCapacity = BigDecimal("100"))

        val neighbor = createLocation("GCF-NEIGH-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        createLocation("GCF-CAND-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        setAllocation(neighbor, BigDecimal("100")) // already full -- excluded via filter 3, not weight
        seedWeightedStock(neighbor, "GCF-NEIGH-$s", BigDecimal("90"))

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("20"), preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `field cap within limit finds the candidate`() {
        val s = suffix()
        val storage = createArea("GCW-ST-$s", listOf("STORAGE"))
        val zone = createZone("GCW-Z-$s")
        val type = createLocationType("GCW-LT-$s", fieldLiftingCapacity = BigDecimal("100"))

        val neighbor = createLocation("GCW-NEIGH-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        val candidate = createLocation("GCW-CAND-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        setAllocation(neighbor, BigDecimal("100"))
        seedWeightedStock(neighbor, "GCW-NEIGH-$s", BigDecimal("50"))

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("20"), preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(candidate)
    }

    // ── section group cap ────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `section cap exceeded by neighbor occupancy plus incoming weight excludes the candidate`() {
        val s = suffix()
        val storage = createArea("GCS-ST-$s", listOf("STORAGE"))
        val zone = createZone("GCS-Z-$s")
        val type = createLocationType("GCS-LT-$s", sectionLiftingCapacity = BigDecimal("100"))

        val neighbor = createLocation("GCS-NEIGH-$s", type, storage, zoneId = zone, section = "S1")
        createLocation("GCS-CAND-$s", type, storage, zoneId = zone, section = "S1")
        setAllocation(neighbor, BigDecimal("100"))
        seedWeightedStock(neighbor, "GCS-NEIGH-$s", BigDecimal("90"))

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("20"), preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    // ── null caps -- regression pin ──────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `null group caps are unrestricted even with heavy neighbor occupancy - regression pin`() {
        val s = suffix()
        val storage = createArea("GCN-ST-$s", listOf("STORAGE"))
        val zone = createZone("GCN-Z-$s")
        // Neither fieldLiftingCapacity nor sectionLiftingCapacity set -- today's default shape
        // of every existing LocationType row.
        val type = createLocationType("GCN-LT-$s")

        val neighbor = createLocation("GCN-NEIGH-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        val candidate = createLocation("GCN-CAND-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        setAllocation(neighbor, BigDecimal("100"))
        seedWeightedStock(neighbor, "GCN-NEIGH-$s", BigDecimal("999999"))

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("500"), preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(candidate)
    }

    // ── degradation path -- no weight data on the group ──────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `unweighed neighbor degrades the check to cap-vs-incoming and still excludes a heavy incoming load`() {
        val s = suffix()
        val storage = createArea("GCD-ST-$s", listOf("STORAGE"))
        val zone = createZone("GCD-Z-$s")
        val type = createLocationType("GCD-LT-$s", fieldLiftingCapacity = BigDecimal("50"))

        val neighbor = createLocation("GCD-NEIGH-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        createLocation("GCD-CAND-$s", type, storage, zoneId = zone, rack = "R1", field = "F1")
        setAllocation(neighbor, BigDecimal("100"))
        // Neighbor carries stock but was NEVER weighed -- contributes zero to the group sum.
        seedWeightedStock(neighbor, "GCD-NEIGH-$s", weight = null)

        // 200kg incoming alone already exceeds the 50kg cap, even though occupancy is zero.
        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("200"), preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    // ── group boundary -- same rack/field, different area ────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `same rack and field in a different area does not count toward the group`() {
        val s = suffix()
        val zone = createZone("GCB-Z-$s")
        val type = createLocationType("GCB-LT-$s", fieldLiftingCapacity = BigDecimal("100"))

        val areaA = createArea("GCB-STA-$s", listOf("STORAGE"))
        val areaB = createArea("GCB-STB-$s", listOf("STORAGE"))
        val candidate = createLocation("GCB-CAND-$s", type, areaA, zoneId = zone, rack = "R1", field = "F1")
        val crossAreaNeighbor = createLocation("GCB-XNEIGH-$s", type, areaB, zoneId = zone, rack = "R1", field = "F1")
        setAllocation(crossAreaNeighbor, BigDecimal("100")) // full -- not itself a candidate
        // Heavy enough that, if wrongly merged into candidate's group, would blow its 100kg cap.
        seedWeightedStock(crossAreaNeighbor, "GCB-XNEIGH-$s", BigDecimal("200"))

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("90"), preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(candidate)
    }

    /**
     * Fix round 1, finding #3 (review): the test above ("same rack and field in a different
     * area...") never actually exercises `FieldKey`'s `areaId` component -- its cross-area
     * neighbor is marked full and so is filtered out of `GroupCapacityReader`'s "needing"
     * candidates before that area is ever queried. THIS test makes BOTH areas simultaneously
     * eligible (neither candidate is full), so both areas genuinely enter
     * `findGroupMembersByAreaIds`'s `areaIds` -- only `FieldKey`'s `areaId` component then
     * prevents them merging into one group. Mutation-verified: removing `areaId` from
     * `FieldKey`/`SectionKey` merges candidateA's heavy same-area neighbor into candidateB's
     * group too, pushing candidateB over the cap and flipping this test's result to NoLocation.
     */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `two simultaneously eligible candidates in different areas are not merged into one group`() {
        val s = suffix()
        val zone = createZone("GCB2-Z-$s")
        val type = createLocationType("GCB2-LT-$s", fieldLiftingCapacity = BigDecimal("100"))

        val areaA = createArea("GCB2-STA-$s", listOf("STORAGE"))
        val areaB = createArea("GCB2-STB-$s", listOf("STORAGE"))

        // Area A: a heavy, already-full neighbor plus an otherwise-eligible candidate -- same
        // rack/field as area B's candidate below.
        val neighborA = createLocation("GCB2-NEIGHA-$s", type, areaA, zoneId = zone, rack = "R1", field = "F1")
        val candidateA = createLocation("GCB2-CANDA-$s", type, areaA, zoneId = zone, rack = "R1", field = "F1")
        // Area B: a lone candidate, SAME rack/field literal, no neighbor of its own -- genuinely
        // eligible (not full), so it enters the "needing" set alongside candidateA.
        val candidateB = createLocation("GCB2-CANDB-$s", type, areaB, zoneId = zone, rack = "R1", field = "F1")

        setAllocation(neighborA, BigDecimal("100")) // full -- excluded via filter 3, but still queried as a group member
        seedWeightedStock(neighborA, "GCB2-NEIGHA-$s", BigDecimal("90"))

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("20"), preferredZoneId = zone))

        // candidateA's group (area A) = neighborA(90) + candidateA -> 90+20=110 > 100, excluded.
        // candidateB's group (area B) = candidateB alone -> 0+20=20 <= 100, allowed -- ONLY if
        // FieldKey correctly keeps area A's and area B's "R1"/"F1" groups apart.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(candidateB)
        assertThat(candidateA).isNotEqualTo(candidateB) // sanity: distinct locations
    }

    // ── null rack/field/section -- full exemption, not a degenerate check ─

    /**
     * Fix round 1, finding #1 (review, null-group-field adjudication): a candidate with no
     * rack/field is not part of any field group at all -- the cap must not apply to it, not
     * degrade to a lone-candidate cap-vs-incoming check.
     */
    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `candidate with no rack or field assigned is fully exempt from the field cap`() {
        val s = suffix()
        val storage = createArea("GCE-ST-$s", listOf("STORAGE"))
        val zone = createZone("GCE-Z-$s")
        val type = createLocationType("GCE-LT-$s", fieldLiftingCapacity = BigDecimal("50"))

        // No rack/field set at all -- "not part of any group" on the field axis.
        val candidate = createLocation("GCE-CAND-$s", type, storage, zoneId = zone)

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; group-weight reads need it set
        // 500kg would blow the 50kg cap if the check ran at all for this candidate -- it must not.
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("500"), preferredZoneId = zone))

        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(candidate)
    }
}

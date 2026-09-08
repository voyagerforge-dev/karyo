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
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Integration test (REAL layout beans + DB) for the putaway location finder.
 *
 * Seeds areas/zones/location-types/locations via the layout REST API, then drives the
 * [LocationFinder] bean directly. Asserts: emptiest STORAGE location wins; locked /
 * full / over-weight / wrong-zone / wrong-client locations are excluded; a soft
 * reservation makes a chosen location count toward full so the next call picks a
 * different one; NoLocation when everything is excluded; and that the
 * [TestReverseLocationFilter] SPI reordering is HONORED (not re-sorted).
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy).
 */
@QuarkusTest
class LocationFinderTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

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

    private fun createLocationType(name: String, liftingCapacity: BigDecimal?): Long {
        val cap = liftingCapacity?.let { ""","liftingCapacity":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$cap}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Suppress("LongParameterList")
    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long? = null): Long {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun lockLocation(id: Long, lockType: Int) {
        given().contentType(ContentType.JSON)
            .body("""{"lockType":$lockType}""")
            .`when`().post("/api/v1/locations/$id/lock")
            .then().statusCode(200)
    }

    /** Directly set base allocation (no REST setter) — simulates an occupied location. */
    @Transactional
    fun setAllocation(id: Long, allocation: BigDecimal) {
        val loc = reservationRepository.getEntityManager()
            .find(com.karyo.layout.domain.model.StorageLocation::class.java, id)
        loc.allocation = allocation
    }

    /** Directly set the goods owner on a location — simulates a client-dedicated bin. */
    @Transactional
    fun setLocationClient(id: Long, ownerClientId: Long) {
        val loc = reservationRepository.getEntityManager()
            .find(com.karyo.layout.domain.model.StorageLocation::class.java, id)
        loc.clientId = ownerClientId
    }

    private fun request(
        reservationKey: Long,
        weight: BigDecimal = BigDecimal.ZERO,
        clientId: Long? = 1L,
        preferredZoneId: Long? = null,
        storageStrategyId: Long? = null,
    ) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = 1L,
        weight = weight,
        clientId = clientId,
        reservationKey = reservationKey,
        preferredZoneId = preferredZoneId,
        storageStrategyId = storageStrategyId,
    )

    private fun suffix() = System.nanoTime()

    /** Own-client (clientA = 1) strategy via the real REST endpoint. `body` overrides/adds
     * JSON fields beyond `name` (e.g. the L6 flags under test). */
    private fun createStrategy(name: String, body: String = "{}"): Long {
        val extra = if (body == "{}") "" else ",${body.trim('{', '}')}"
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$extra}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `picks emptiest STORAGE location and excludes locked, full, over-weight`() {
        val s = suffix()
        // Each test scopes to its own zone (and requests preferredZoneId) so the shared
        // app DB's other STORAGE locations never bleed into the candidate set.
        val storage = createArea("ST-$s", listOf("STORAGE"))
        val zone = createZone("ZF-$s")
        val type = createLocationType("LT-$s", BigDecimal("1000"))

        val empty = createLocation("LF-EMPTY-$s", type, storage, zoneId = zone)
        val half = createLocation("LF-HALF-$s", type, storage, zoneId = zone)
        val locked = createLocation("LF-LOCKED-$s", type, storage, zoneId = zone)

        setAllocation(half, BigDecimal("50"))   // half full — should lose to the empty one
        lockLocation(locked, 1)                   // any non-zero lock

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(empty)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `excludes over-weight locations via lifting capacity`() {
        val s = suffix()
        val storage = createArea("STW-$s", listOf("STORAGE"))
        val zone = createZone("ZW-$s")
        val light = createLocationType("LT-LIGHT-$s", BigDecimal("100"))
        val heavy = createLocationType("LT-HEAVY-$s", BigDecimal("5000"))

        createLocation("LW-LIGHT-$s", light, storage, zoneId = zone)
        val heavyLoc = createLocation("LW-HEAVY-$s", heavy, storage, zoneId = zone)

        // A 500kg load: only the 5000-capacity location qualifies.
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, weight = BigDecimal("500"), preferredZoneId = zone))
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(heavyLoc)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `honors preferred zone`() {
        val s = suffix()
        val storage = createArea("STZ-$s", listOf("STORAGE"))
        val type = createLocationType("LTZ-$s", BigDecimal("1000"))
        val zoneA = createZone("ZA-$s")
        val zoneB = createZone("ZB-$s")

        createLocation("LZ-A-$s", type, storage, zoneId = zoneA)
        val inB = createLocation("LZ-B-$s", type, storage, zoneId = zoneB)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zoneB))
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(inB)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `excludes locations dedicated to a different client`() {
        val s = suffix()
        val storage = createArea("STC-$s", listOf("STORAGE"))
        val zone = createZone("ZC-$s")
        val type = createLocationType("LTC-$s", BigDecimal("1000"))

        val otherClientBin = createLocation("LC-OTHER-$s", type, storage, zoneId = zone)
        val ownBin = createLocation("LC-OWN-$s", type, storage, zoneId = zone)
        setLocationClient(otherClientBin, 999L) // dedicated to a different goods owner

        // request clientId = 1, mixClient default false → other-client bin excluded.
        val result = locationFinder.findPutawayLocation(request(reservationKey = s, clientId = 1L, preferredZoneId = zone))
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(ownBin)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `reservation makes a chosen location count toward full so next call picks another`() {
        val s = suffix()
        val storage = createArea("STR-$s", listOf("STORAGE"))
        val zone = createZone("ZR-$s")
        val type = createLocationType("LTR-$s", BigDecimal("1000"))

        val a = createLocation("LR-A-$s", type, storage, zoneId = zone)
        val b = createLocation("LR-B-$s", type, storage, zoneId = zone)

        // First call reserves one of them (100% TTL reservation).
        val first = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone)) as LocationFinderResult.Found
        // Second call must pick the OTHER one — the first is now effectively full.
        val second = locationFinder.findPutawayLocation(request(reservationKey = s + 1, preferredZoneId = zone)) as LocationFinderResult.Found

        assertThat(setOf(first.locationId, second.locationId)).isEqualTo(setOf(a, b))
        assertThat(first.locationId).isNotEqualTo(second.locationId)

        // Releasing the first reservation frees it again.
        locationFinder.releaseReservation(s)
        assertThat(reservationRepository.findByTransportOrderId(s)).isEmpty()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `returns NoLocation with a reason when all candidates are excluded`() {
        val s = suffix()
        // Only a PICKING location exists in this test's zone — no STORAGE candidate for
        // a (default) putaway scoped to that zone.
        val picking = createArea("PK-$s", listOf("PICKING"))
        val zone = createZone("ZN-$s")
        val type = createLocationType("LTN-$s", BigDecimal("1000"))
        createLocation("LN-PICK-$s", type, picking, zoneId = zone)

        val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone))
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
        assertThat((result as LocationFinderResult.NoLocation).reason).contains("STORAGE")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `SPI LocationFilter reordering is honored - finder does not re-sort`() {
        val s = suffix()
        val storage = createArea("STF-$s", listOf("STORAGE"))
        val zone = createZone("ZSPI-$s")
        val type = createLocationType("LTF-$s", BigDecimal("1000"))

        // Two empty locations: by finder order (allocation ASC, name ASC) "LF-A" is first.
        val a = createLocation("LF-A-$s", type, storage, zoneId = zone)
        val b = createLocation("LF-B-$s", type, storage, zoneId = zone)

        // The TestReverseLocationFilter reverses the list → "LF-B" becomes first.
        // If the finder honored the SPI order, it returns B (the larger-name one), not A.
        TestReverseLocationFilter.enabled = true
        try {
            val result = locationFinder.findPutawayLocation(request(reservationKey = s, preferredZoneId = zone)) as LocationFinderResult.Found
            assertThat(result.locationId).isEqualTo(b)
            assertThat(result.locationId).isNotEqualTo(a)
        } finally {
            TestReverseLocationFilter.enabled = false
        }
    }

    // ── L6 flags: manualSearch / onlyClientLocation (V312) ─────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `strategy with all-default V312 flags behaves identically to no strategy at all`() {
        val s = suffix()
        val storage = createArea("STD-$s", listOf("STORAGE"))
        val zone = createZone("ZD-$s")
        val type = createLocationType("LTD-$s", BigDecimal("1000"))

        val empty = createLocation("LD-EMPTY-$s", type, storage, zoneId = zone)
        val half = createLocation("LD-HALF-$s", type, storage, zoneId = zone)
        setAllocation(half, BigDecimal("50"))

        // No flags in the create body -> manualSearch/useAreaStrategyDate/useItemDataArea
        // default false; onlyClientLocation defaults true (row 17, defect-burndown-4) --
        // irrelevant here, both candidates are the requesting client's own. Routing the
        // request through this strategy must reproduce the exact "picks emptiest" outcome the
        // flagless test above pins.
        val strategyId = createStrategy("DEFAULTS-$s")

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(empty)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `manualSearch=true suggests nothing even with a valid candidate present`() {
        val s = suffix()
        val storage = createArea("SMS-$s", listOf("STORAGE"))
        val zone = createZone("ZMS-$s")
        val type = createLocationType("LTMS-$s", BigDecimal("1000"))

        // A perfectly good, empty, unlocked STORAGE candidate -- would be Found without
        // the flag (see the flagless "picks emptiest" test).
        createLocation("LMS-CAND-$s", type, storage, zoneId = zone)

        val strategyId = createStrategy("MANUAL-$s", """{"manualSearch":true}""")

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
        assertThat((result as LocationFinderResult.NoLocation).reason).contains("manual")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `onlyClientLocation=true excludes a shared location, leaving only the owner's own`() {
        val s = suffix()
        val storage = createArea("SOC-$s", listOf("STORAGE"))
        val zone = createZone("ZOC-$s")
        val type = createLocationType("LTOC-$s", BigDecimal("1000"))

        val shared = createLocation("LOC-SHARED-$s", type, storage, zoneId = zone)
        setLocationClient(shared, 0L) // shared sentinel
        val own = createLocation("LOC-OWN-$s", type, storage, zoneId = zone) // stamped clientId=1 (own)

        val strategyId = createStrategy("ONLYCLIENT-$s", """{"onlyClientLocation":true}""")

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(own)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `onlyClientLocation=true with only a shared candidate finds nothing`() {
        val s = suffix()
        val storage = createArea("SOCN-$s", listOf("STORAGE"))
        val zone = createZone("ZOCN-$s")
        val type = createLocationType("LTOCN-$s", BigDecimal("1000"))

        val shared = createLocation("LOC-ONLYSHARED-$s", type, storage, zoneId = zone)
        setLocationClient(shared, 0L)

        val strategyId = createStrategy("ONLYCLIENTN-$s", """{"onlyClientLocation":true}""")

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.NoLocation::class.java)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `onlyClientLocation=false is the sanctioned explicit opt-in - still finds a shared location`() {
        val s = suffix()
        val storage = createArea("SOCF-$s", listOf("STORAGE"))
        val zone = createZone("ZOCF-$s")
        val type = createLocationType("LTOCF-$s", BigDecimal("1000"))

        val shared = createLocation("LOC-SHAREDOK-$s", type, storage, zoneId = zone)
        setLocationClient(shared, 0L)

        // Row 17 (defect-burndown-4): onlyClientLocation now DEFAULTS true, so this test must
        // set it explicitly false to exercise the opt-in path -- the only route left to a
        // shared location.
        val strategyId = createStrategy("ONLYCLIENTF-$s", """{"onlyClientLocation":false}""")

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(shared)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `onlyClientLocation defaults to true - no strategy at all excludes a shared location`() {
        val s = suffix()
        val storage = createArea("SOCD-$s", listOf("STORAGE"))
        val zone = createZone("ZOCD-$s")
        val type = createLocationType("LTOCD-$s", BigDecimal("1000"))

        val shared = createLocation("LOC-SHAREDDEF-$s", type, storage, zoneId = zone)
        setLocationClient(shared, 0L) // shared sentinel
        val own = createLocation("LOC-OWNDEF-$s", type, storage, zoneId = zone) // stamped clientId=1 (own)

        // No storageStrategyId at all -- the finder falls back to
        // LocationFinderService.DEFAULT_ONLY_CLIENT_LOCATION, which row 17 flipped to true.
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(own)
    }
}

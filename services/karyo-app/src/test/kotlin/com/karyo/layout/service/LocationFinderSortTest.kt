package com.karyo.layout.service

import com.karyo.layout.domain.model.LocationCluster
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.domain.model.StorageStrategy
import com.karyo.layout.domain.model.TypeCapacityConstraint
import com.karyo.layout.domain.model.Zone
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.spi.LocationFinderResult
import com.karyo.layout.vo.StorageStrategySortType
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
 * Tests for the locations-layout sprint's Task 5: the [StorageStrategySortType] comparator
 * chain ([CandidateOrdering]) and its wiring into [LocationFinderService].
 *
 * Two deliberately different testing styles, kept in this one sibling file (per the sprint's
 * established large-class split — see [LocationFinderAreaTest]/[LocationFinderCapacityTest]):
 *
 *  - Per-sort-type tests [@Inject] [CandidateOrdering] directly and hand it two hand-built
 *    candidates differing ONLY in the dimension under test, with names deliberately chosen to
 *    fight the name/id tiebreak — this is what actually "proves the ordering flips candidate
 *    choice" per sort type. It also sidesteps filter 4's exact-zone-match SQL predicate
 *    (`StorageLocationRepository.findPutawayCandidates`), which would otherwise make a
 *    mixed-zone candidate set for `ZONE` unreachable through the full `findPutawayLocation`
 *    pipeline (myWMS's own zone filter is a `zone IN (:zones)` list; Karyo's is a single exact
 *    match — an existing, out-of-scope-for-this-task simplification).
 *  - A single combined full-pipeline test drives the real [LocationFinder.findPutawayLocation]
 *    to pin the empty-sorts regression AND prove the `sorts` wiring actually reaches the real
 *    finder (not just the isolated comparator function).
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy), same as the sprint's
 * other [LocationFinderService] test siblings.
 */
@QuarkusTest
class LocationFinderSortTest {

    @Inject
    lateinit var candidateOrdering: CandidateOrdering

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    private val client = 1L

    private fun suffix() = System.nanoTime()

    // ── REST seeding helpers (duplicated from LocationFinderAreaTest/CapacityTest per the
    // sprint's established pattern for this concern-split) ──

    private fun createZone(name: String, overflowZoneId: Long? = null): Long {
        val overflowPart = overflowZoneId?.let { ""","overflowZoneId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$overflowPart}""")
            .`when`().post("/api/v1/zones")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** STORAGE area + a liftingCapacity=1000 location type, merged into one helper — both
     * `nearPickingLocation` and the full-pipeline test need exactly this pair, nothing more. */
    private fun seedAreaAndType(prefix: String): Pair<Long, Long> {
        val areaId = given().contentType(ContentType.JSON)
            .body("""{"name":"$prefix-ST","usages":["STORAGE"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        val typeId = given().contentType(ContentType.JSON)
            .body("""{"name":"$prefix-LT","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
        return areaId to typeId
    }

    @Suppress("LongParameterList")
    private fun createLocation(
        name: String,
        typeId: Long,
        areaId: Long,
        zoneId: Long? = null,
        xPos: Int = 0,
        rack: String? = null,
    ): Long {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        val rackPart = rack?.let { ""","rack":"$it"""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart,"xPos":$xPos$rackPart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createStrategy(name: String, body: String = "{}"): Long {
        val extra = if (body == "{}") "" else ",${body.trim('{', '}')}"
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$extra}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createLocationCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStorageArea(name: String, clusterIds: List<Long>): Long {
        val ids = clusterIds.joinToString(",")
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":[$ids]}""")
            .`when`().post("/api/v1/storage-areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Item-unit + product creation, merged into one helper — only used by the
     * `nearPickingLocation` test, which needs a resolvable `itemDataId`, nothing else. */
    private fun createProductForFix(s: Long): Long {
        val itemUnitId = given().contentType(ContentType.JSON)
            .body("""{"name":"SORT-NP-IU-${s.toString().takeLast(8)}","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")
        return given().contentType(ContentType.JSON)
            .body("""{"number":"SORT-NP-SKU-$s","name":"Sort Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createFixAssignment(locationId: Long, itemDataId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Directly set base allocation (no REST setter) — mirrors [LocationFinderTest.setAllocation]. */
    @Transactional
    fun setAllocation(id: Long, allocation: BigDecimal) {
        val loc = reservationRepository.getEntityManager().find(StorageLocation::class.java, id)
        loc.allocation = allocation
    }

    // ── Direct-CandidateOrdering test builders ──────────────────────────

    private fun candidate(
        name: String,
        id: Long,
        eff: BigDecimal = BigDecimal.ZERO,
        block: StorageLocation.() -> Unit = {},
    ): Pair<StorageLocation, BigDecimal> {
        val loc = StorageLocation().apply {
            this.name = name
            this.id = id
            this.clientId = client
            block()
        }
        return loc to eff
    }

    private fun strategyWithSorts(
        sorts: String?,
        useAreaStrategyDate: Boolean = false,
        nearPickingLocation: Boolean = false,
        id: Long? = null,
    ): StorageStrategy = StorageStrategy().apply {
        this.name = "direct-test"
        this.sorts = sorts
        this.useAreaStrategyDate = useAreaStrategyDate
        this.nearPickingLocation = nearPickingLocation
        this.id = id
    }

    private fun req(itemDataId: Long? = null, unitLoadTypeId: Long? = null) = LocationFinderRequest(
        unitLoadId = 1L,
        unitLoadTypeId = unitLoadTypeId,
        weight = BigDecimal.ZERO,
        clientId = client,
        reservationKey = 1L,
        itemDataId = itemDataId,
    )

    // ── Per-sort-type tests (direct CandidateOrdering) ──────────────────

    @Test
    fun `CLIENT sort ranks the requesting owner's own location before a shared one`() {
        val own = candidate("Z-OWN", 1L) { clientId = client }
        val shared = candidate("A-SHARED", 2L) { clientId = 0L }
        val ordered = candidateOrdering.apply(listOf(shared, own), req(), strategyWithSorts("CLIENT"), emptyMap(), null)
        assertThat(ordered.first().first.name).isEqualTo("Z-OWN")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `STORAGEAREA sort orders by area orderIndex, and area order still wins when useAreaStrategyDate prepends it`() {
        val s = suffix()
        val clusterA = createLocationCluster("SORT-CA-$s")
        val clusterB = createLocationCluster("SORT-CB-$s")
        val areaA = createStorageArea("SORT-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("SORT-AB-$s", listOf(clusterB))
        val strategyId = createStrategy("SORT-STRAT-$s")
        given().contentType(ContentType.JSON)
            .body("[$areaA,$areaB]")
            .`when`().put("/api/v1/storage-strategies/$strategyId/areas")
            .then().statusCode(200)

        // Names deliberately fight the area order: cluster A's location sorts LAST
        // alphabetically, cluster B's sorts FIRST — only the area-orderIndex comparator
        // (not the name tiebreak) can make A's location win.
        val locInB = candidate("A-IN-B", 1L) { locationCluster = LocationCluster().apply { id = clusterB } }
        val locInA = candidate("Z-IN-A", 2L) { locationCluster = LocationCluster().apply { id = clusterA } }

        val active = strategyWithSorts("STORAGEAREA", id = strategyId)
        val orderedActive = candidateOrdering.apply(listOf(locInB, locInA), req(), active, emptyMap(), null)
        assertThat(orderedActive.first().first.name).isEqualTo("Z-IN-A")

        // Final-review F2 fix: under useAreaStrategyDate, `STORAGEAREA` in `sorts` is SKIPPED
        // as redundant, but the SAME area order is now prepended ahead of the (remaining,
        // empty) sorts chain — area A (orderIndex 0) still wins, exactly as it does when
        // STORAGEAREA is explicit above. Before the fix this asserted "A-IN-B" (name winning
        // once area ordering was dropped entirely) — that was the bug, not the spec.
        val suppressed = strategyWithSorts("STORAGEAREA", useAreaStrategyDate = true, id = strategyId)
        val orderedSuppressed = candidateOrdering.apply(listOf(locInB, locInA), req(), suppressed, emptyMap(), null)
        assertThat(orderedSuppressed.first().first.name).isEqualTo("Z-IN-A")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `F2 - useAreaStrategyDate area-order prepend survives a non-empty unrelated sorts chain`() {
        val s = suffix()
        val clusterA = createLocationCluster("SORT-F2-CA-$s")
        val clusterB = createLocationCluster("SORT-F2-CB-$s")
        val areaA = createStorageArea("SORT-F2-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("SORT-F2-AB-$s", listOf(clusterB))
        val strategyId = createStrategy("SORT-F2-STRAT-$s")
        given().contentType(ContentType.JSON)
            .body("[$areaA,$areaB]")
            .`when`().put("/api/v1/storage-strategies/$strategyId/areas")
            .then().statusCode(200)

        // B's candidate has the HIGHER allocation -- ALLOCATION sorts DESCENDING (fullest
        // first, consolidation), so under a plain ALLOCATION chain B would win. If the
        // area-order prepend were lost (the F2 regression), ALLOCATION would decide and B
        // would win despite coming after A in the strategy's area list. (Final-review
        // mutation check: the original fixture had the polarity inverted -- both variants
        // agreed on A -- making this test non-discriminating; fixed so dropping the prepend
        // now flips the answer to B and fails this assertion.)
        val locInB = candidate("Z-IN-B-FULLER", 1L, eff = BigDecimal("80")) {
            locationCluster = LocationCluster().apply { id = clusterB }
        }
        val locInA = candidate("A-IN-A-EMPTIER", 2L, eff = BigDecimal("10")) {
            locationCluster = LocationCluster().apply { id = clusterA }
        }

        val strategy = strategyWithSorts("ALLOCATION", useAreaStrategyDate = true, id = strategyId)
        val ordered = candidateOrdering.apply(listOf(locInB, locInA), req(), strategy, emptyMap(), null)
        assertThat(ordered.first().first.name).isEqualTo("A-IN-A-EMPTIER")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `ZONE sort ranks by the resolved zone's overflow chain, unmatched zones last`() {
        val s = suffix()
        val zoneB = createZone("SORT-ZB-$s")
        val zoneA = createZone("SORT-ZA-$s", overflowZoneId = zoneB)
        val zoneC = createZone("SORT-ZC-$s") // outside the A -> B chain entirely

        val inB = candidate("A-IN-ZB", 1L) { zone = Zone().apply { id = zoneB } }
        val inA = candidate("Z-IN-ZA", 2L) { zone = Zone().apply { id = zoneA } }
        val outside = candidate("0-OUTSIDE", 3L) { zone = Zone().apply { id = zoneC } }

        val ordered = candidateOrdering.apply(listOf(outside, inB, inA), req(), strategyWithSorts("ZONE"), emptyMap(), zoneA)
        assertThat(ordered.map { it.first.name }).containsExactly("Z-IN-ZA", "A-IN-ZB", "0-OUTSIDE")
    }

    @Test
    fun `CAPACITY sort ranks by the matched TypeCapacityConstraint's orderIndex`() {
        val fastType = LocationType().apply { id = 501_001L }
        val slowType = LocationType().apply { id = 501_002L }
        val ulTypeId = 909_001L
        val constraintsByType = mapOf(
            fastType.id!! to listOf(TypeCapacityConstraint().apply { locationType = fastType; unitLoadTypeId = ulTypeId; orderIndex = 0 }),
            slowType.id!! to listOf(TypeCapacityConstraint().apply { locationType = slowType; unitLoadTypeId = ulTypeId; orderIndex = 5 }),
        )
        val fast = candidate("Z-FAST", 1L) { locationType = fastType }
        val slow = candidate("A-SLOW", 2L) { locationType = slowType }

        val ordered = candidateOrdering.apply(
            listOf(slow, fast), req(unitLoadTypeId = ulTypeId), strategyWithSorts("CAPACITY"), constraintsByType, null
        )
        assertThat(ordered.first().first.name).isEqualTo("Z-FAST")
    }

    @Test
    fun `ALLOCATION sort ranks effective allocation DESC - consolidates into the fuller location`() {
        val emptier = candidate("A-EMPTY", 1L, eff = BigDecimal("10"))
        val fuller = candidate("Z-FULL", 2L, eff = BigDecimal("80"))
        val ordered = candidateOrdering.apply(listOf(emptier, fuller), req(), strategyWithSorts("ALLOCATION"), emptyMap(), null)
        assertThat(ordered.first().first.name).isEqualTo("Z-FULL")
    }

    @Test
    fun `POSITION_X and POSITION_Y sort rank by coordinate ascending`() {
        val farX = candidate("A-FARX", 1L) { xPos = 100 }
        val nearX = candidate("Z-NEARX", 2L) { xPos = 1 }
        val orderedX = candidateOrdering.apply(listOf(farX, nearX), req(), strategyWithSorts("POSITION_X"), emptyMap(), null)
        assertThat(orderedX.first().first.name).isEqualTo("Z-NEARX")

        val farY = candidate("A-FARY", 3L) { yPos = 100 }
        val nearY = candidate("Z-NEARY", 4L) { yPos = 1 }
        val orderedY = candidateOrdering.apply(listOf(farY, nearY), req(), strategyWithSorts("POSITION_Y"), emptyMap(), null)
        assertThat(orderedY.first().first.name).isEqualTo("Z-NEARY")
    }

    @Test
    fun `ORDERINDEX sort ranks by location orderIndex ascending`() {
        val late = candidate("A-LATE", 1L) { orderIndex = 9 }
        val early = candidate("Z-EARLY", 2L) { orderIndex = 0 }
        val ordered = candidateOrdering.apply(listOf(late, early), req(), strategyWithSorts("ORDERINDEX"), emptyMap(), null)
        assertThat(ordered.first().first.name).isEqualTo("Z-EARLY")
    }

    @Test
    fun `NAME sort ranks ascending regardless of input order`() {
        val z = candidate("Z-CANDIDATE", 1L)
        val a = candidate("A-CANDIDATE", 2L)
        val ordered = candidateOrdering.apply(listOf(z, a), req(), strategyWithSorts("NAME"), emptyMap(), null)
        assertThat(ordered.map { it.first.name }).containsExactly("A-CANDIDATE", "Z-CANDIDATE")
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `nearPickingLocation prepends fix-assignment distance, only when the flag is set and a fix resolves`() {
        val s = suffix()
        val (storage, type) = seedAreaAndType("SORT-NP-$s")
        val fixLocId = createLocation("SORT-NP-FIX-$s", type, storage, xPos = 50)
        val itemDataId = createProductForFix(s)
        createFixAssignment(fixLocId, itemDataId)

        val near = candidate("Z-NEAR", 1L) { xPos = 55 } // |55-50| = 5
        val far = candidate("A-FAR", 2L) { xPos = 5 } // |5-50| = 45

        // Active + resolvable fix: the nearer candidate wins despite its name sorting after "A-FAR".
        val active = strategyWithSorts(sorts = null, nearPickingLocation = true)
        val orderedActive = candidateOrdering.apply(listOf(far, near), req(itemDataId = itemDataId), active, emptyMap(), null)
        assertThat(orderedActive.first().first.name).isEqualTo("Z-NEAR")

        // Active but no FixAssignment resolves for this itemDataId -> nothing prepended.
        val orderedNoFix = candidateOrdering.apply(listOf(far, near), req(itemDataId = 999_888_777L), active, emptyMap(), null)
        assertThat(orderedNoFix).containsExactly(far, near)

        // Flag false -> nothing prepended even though a fix DOES resolve for this product.
        val inactive = strategyWithSorts(sorts = null, nearPickingLocation = false)
        val orderedFlagOff = candidateOrdering.apply(listOf(far, near), req(itemDataId = itemDataId), inactive, emptyMap(), null)
        assertThat(orderedFlagOff).containsExactly(far, near)
    }

    // ── nearPickingLocation: same-rack preference (LF8b, finder filter 2) ──────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `nearPickingLocation prefers candidates in the fix assignment's rack over closer X-distance in another rack`() {
        val s = suffix()
        val (storage, type) = seedAreaAndType("SORT-NPR1-$s")
        val fixLocId = createLocation("SORT-NPR1-FIX-$s", type, storage, xPos = 0, rack = "R1")
        val itemDataId = createProductForFix(s)
        createFixAssignment(fixLocId, itemDataId)

        // A is closer in raw X-distance (|1-0|=1 vs |50-0|=50) but sits in a different rack.
        // B shares the fix's rack, so same-rack preference must outrank X-distance.
        val closerOtherRack = candidate("A-CLOSER", 1L) { xPos = 1; rack = "R2" }
        val sameRackFarther = candidate("Z-SAMERACK", 2L) { xPos = 50; rack = "R1" }

        val strategy = strategyWithSorts(sorts = null, nearPickingLocation = true)
        val ordered = candidateOrdering.apply(
            listOf(closerOtherRack, sameRackFarther), req(itemDataId = itemDataId), strategy, emptyMap(), null
        )
        assertThat(ordered.first().first.name).isEqualTo("Z-SAMERACK")
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `rack preference falls back to X-distance when no candidate shares the rack`() {
        val s = suffix()
        val (storage, type) = seedAreaAndType("SORT-NPR2-$s")
        val fixLocId = createLocation("SORT-NPR2-FIX-$s", type, storage, xPos = 0, rack = "R1")
        val itemDataId = createProductForFix(s)
        createFixAssignment(fixLocId, itemDataId)

        // Neither candidate shares the fix's rack ("R1") -- both fall to the general
        // X-distance ordering, so the nearer one (D, distance 5) wins over C (distance 30).
        val fartherRack2 = candidate("A-FARTHER", 1L) { xPos = 30; rack = "R2" }
        val nearerRack3 = candidate("Z-NEARER", 2L) { xPos = 5; rack = "R3" }

        val strategy = strategyWithSorts(sorts = null, nearPickingLocation = true)
        val ordered = candidateOrdering.apply(
            listOf(fartherRack2, nearerRack3), req(itemDataId = itemDataId), strategy, emptyMap(), null
        )
        assertThat(ordered.first().first.name).isEqualTo("Z-NEARER")
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a fix location without a rack leaves ordering purely X-distance - regression pin`() {
        val s = suffix()
        val (storage, type) = seedAreaAndType("SORT-NPR3-$s")
        val fixLocId = createLocation("SORT-NPR3-FIX-$s", type, storage, xPos = 0)
        val itemDataId = createProductForFix(s)
        createFixAssignment(fixLocId, itemDataId)

        val farther = candidate("A-FARTHER", 1L) { xPos = 100; rack = "R1" }
        val nearer = candidate("Z-NEARER", 2L) { xPos = 10; rack = "R9" }

        val strategy = strategyWithSorts(sorts = null, nearPickingLocation = true)
        val ordered = candidateOrdering.apply(
            listOf(farther, nearer), req(itemDataId = itemDataId), strategy, emptyMap(), null
        )
        assertThat(ordered.first().first.name).isEqualTo("Z-NEARER")
    }

    // ── Parser ────────────────────────────────────────────────────────────

    @Test
    fun `parser skips unknown sorts tokens at read time, keeping recognized ones in order`() {
        val parsed = StorageStrategySortParser.parseLenient("CLIENT, bogus ,ZONE,, another_bad,NAME")
        assertThat(parsed).containsExactly(
            StorageStrategySortType.CLIENT, StorageStrategySortType.ZONE, StorageStrategySortType.NAME,
        )
    }

    // ── Full pipeline: regression pin + wiring proof ─────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `full pipeline - empty sorts regression-pins today's ordering, ALLOCATION sort flips it`() {
        val s = suffix()
        val (storage, type) = seedAreaAndType("SORT-PIPE-$s")
        val zone = createZone("SORT-PIPE-Z-$s")
        val emptier = createLocation("SORT-PIPE-EMPTY-$s", type, storage, zoneId = zone)
        val fuller = createLocation("SORT-PIPE-FULL-$s", type, storage, zoneId = zone)
        setAllocation(emptier, BigDecimal("10"))
        setAllocation(fuller, BigDecimal("80"))

        // No strategy at all -> today's default (emptiest first) ordering, untouched by Task 5.
        val plain = locationFinder.findPutawayLocation(
            LocationFinderRequest(
                unitLoadId = 9000 + s, unitLoadTypeId = 1L, weight = BigDecimal.ZERO,
                clientId = client, reservationKey = s, preferredZoneId = zone,
            )
        )
        assertThat(plain).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((plain as LocationFinderResult.Found).locationId).isEqualTo(emptier)
        locationFinder.releaseReservation(s)

        // An ALLOCATION-sort strategy flips the pick to the fuller (consolidating) location.
        val strategyId = createStrategy("SORT-PIPE-STRAT-$s", """{"sorts":"ALLOCATION"}""")
        val sorted = locationFinder.findPutawayLocation(
            LocationFinderRequest(
                unitLoadId = 9001 + s, unitLoadTypeId = 1L, weight = BigDecimal.ZERO,
                clientId = client, reservationKey = s + 1, preferredZoneId = zone, storageStrategyId = strategyId,
            )
        )
        assertThat(sorted).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((sorted as LocationFinderResult.Found).locationId).isEqualTo(fuller)
    }
}

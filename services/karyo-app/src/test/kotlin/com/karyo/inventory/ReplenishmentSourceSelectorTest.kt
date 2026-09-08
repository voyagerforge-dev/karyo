package com.karyo.inventory

import com.karyo.inventory.api.spi.ReplenishmentSourceSelector
import com.karyo.inventory.api.spi.SourceQuery
import com.karyo.inventory.service.StockService
import com.karyo.security.TenantContext
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
 * Integration test for [ReplenishmentSourceSelector] — R14 lot-aware + fixed-reserve-first
 * tiered source selection. See that interface's KDoc for the full algorithm.
 *
 * Seeding pattern mirrors DefaultStockPickerTest / DefaultStockReserverTest:
 * REST helpers (authed via @TestSecurity + @OidcSecurity) create unit loads at specific
 * storageLocationIds, then create ON_STOCK (state=300) stock units on them. The JWT
 * client_id claim sets clientId on every created entity. `selectSource` is called directly, so
 * `SourceQuery.faceLotNumbers`/`fixFaceLocationIds` are supplied explicitly per test rather than
 * derived from real fix-assignment/face-stock fixtures (that end-to-end wiring is exercised by
 * `ReplenishmentTopUpFlowTest`/`ReplenishmentServiceTest`).
 *
 * Each test uses its own dedicated clientId (6001-6017 range) to isolate from other suites and
 * from each other.
 */
@QuarkusTest
class ReplenishmentSourceSelectorTest {

    @Inject
    lateinit var selector: ReplenishmentSourceSelector

    @Inject
    lateinit var stockService: StockService

    @Inject
    lateinit var tenantContext: TenantContext

    // ── seed helpers ────────────────────────────────────────────────────────

    /** Creates a unit-load at the given storageLocationId; returns the UL id. */
    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}"""
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    /** Creates an ON_STOCK (state=300) stock unit on the given UL; returns the stock-unit id. */
    private fun createStock(ulId: Long, itemDataId: Long, amount: Double, lotNumber: String? = null): Long {
        val lotPart = lotNumber?.let { ""","lotNumber":"$it"""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"RSS-$itemDataId",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300$lotPart}"""
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun baseQuery(
        itemDataId: Long,
        clientId: Long,
        targetLocationId: Long,
        faceLotNumbers: Set<String> = emptySet(),
        targetIsPickingFace: Boolean = false,
        fromPicking: Boolean = false,
        fixFaceLocationIds: Set<Long> = emptySet(),
        excludeUnitLoadIds: Set<Long> = emptySet(),
    ) = SourceQuery(
        itemDataId = itemDataId,
        clientId = clientId,
        targetLocationId = targetLocationId,
        faceLotNumbers = faceLotNumbers,
        targetIsPickingFace = targetIsPickingFace,
        fromPicking = fromPicking,
        fixFaceLocationIds = fixFaceLocationIds,
        excludeUnitLoadIds = excludeUnitLoadIds,
    )

    // ── Test 1: reserve UL is selected; face UL is excluded ─────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6001"), Claim(key = "tenant_code", value = "ACME")])
    fun `selects the FIFO reserve unit-load and excludes fix-face locations`() {
        val itemDataId = 6001_001L
        val suffix = System.nanoTime()

        // Face UL at locationId 6100 (will be excluded)
        val faceLocId = 6100L
        val faceUlId = createUnitLoad("UL-RSS-FACE-$suffix", faceLocId)
        createStock(faceUlId, itemDataId, 50.0)

        // Reserve UL at locationId 6200 (should be selected)
        val reserveLocId = 6200L
        val reserveUlId = createUnitLoad("UL-RSS-RESV-$suffix", reserveLocId)
        createStock(reserveUlId, itemDataId, 80.0)

        val source = selector.selectSource(baseQuery(itemDataId, 6001L, targetLocationId = faceLocId))

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(reserveUlId)
        assertThat(source.amount).isEqualByComparingTo("80.0")
    }

    // ── Test 2: null when only stock is on the excluded target face ─────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6002"), Claim(key = "tenant_code", value = "ACME")])
    fun `returns null when the only stock is on the excluded target face -- no eligible source`() {
        val itemDataId = 6002_001L
        val suffix = System.nanoTime()

        // Only a face UL -- no reserve stock
        val faceLocId = 6300L
        val faceUlId = createUnitLoad("UL-RSS-ONLY-$suffix", faceLocId)
        createStock(faceUlId, itemDataId, 40.0)

        val source = selector.selectSource(baseQuery(itemDataId, 6002L, targetLocationId = faceLocId))

        assertThat(source).isNull()
    }

    // ── Test 3: lot match preferred over a FIFO-older, different-lot candidate ──

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6010"), Claim(key = "tenant_code", value = "ACME")])
    fun `a lot-matching candidate is preferred over a FIFO-older candidate on a different lot`() {
        val itemDataId = 6010_001L
        val suffix = System.nanoTime()

        // Created FIRST -- FIFO-older -- but a DIFFERENT lot.
        val olderUlId = createUnitLoad("UL-LOT-OLD-$suffix", 6111L)
        createStock(olderUlId, itemDataId, 40.0, lotNumber = "LOT-OLD")

        // Created SECOND -- FIFO-younger -- matches the face's lot.
        val matchingUlId = createUnitLoad("UL-LOT-MATCH-$suffix", 6112L)
        createStock(matchingUlId, itemDataId, 40.0, lotNumber = "LOT-MATCH")

        val source = selector.selectSource(
            baseQuery(itemDataId, 6010L, targetLocationId = 6110L, faceLotNumbers = setOf("LOT-MATCH")),
        )

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(matchingUlId)
    }

    // ── Test 4: fixed-reserve source chosen before general storage ──────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6011"), Claim(key = "tenant_code", value = "ACME")])
    fun `a fix-assigned reserve location is preferred over a FIFO-older general storage location`() {
        val itemDataId = 6011_001L
        val suffix = System.nanoTime()

        // Created FIRST -- FIFO-older -- but NOT a fix-assigned location.
        val generalUlId = createUnitLoad("UL-GEN-$suffix", 6121L)
        createStock(generalUlId, itemDataId, 40.0)

        // Created SECOND -- FIFO-younger -- but IS a fix-assigned reserve location.
        val reserveUlId = createUnitLoad("UL-RESV-FIX-$suffix", 6122L)
        createStock(reserveUlId, itemDataId, 40.0)

        val source = selector.selectSource(
            baseQuery(itemDataId, 6011L, targetLocationId = 6120L, fixFaceLocationIds = setOf(6122L)),
        )

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(reserveUlId)
    }

    // ── Test 5: lot preference outranks tiering ──────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6012"), Claim(key = "tenant_code", value = "ACME")])
    fun `a matching lot in general storage beats a different lot on a fixed reserve slot`() {
        val itemDataId = 6012_001L
        val suffix = System.nanoTime()

        // Fix-assigned reserve slot, created FIRST (FIFO-older too) -- but the WRONG lot.
        val reserveWrongLotUlId = createUnitLoad("UL-RESV-WRONGLOT-$suffix", 6131L)
        createStock(reserveWrongLotUlId, itemDataId, 40.0, lotNumber = "LOT-X")

        // General storage, created SECOND -- but matches the face's lot.
        val generalMatchLotUlId = createUnitLoad("UL-GEN-MATCHLOT-$suffix", 6132L)
        createStock(generalMatchLotUlId, itemDataId, 40.0, lotNumber = "LOT-MATCH")

        val source = selector.selectSource(
            baseQuery(
                itemDataId,
                6012L,
                targetLocationId = 6130L,
                faceLotNumbers = setOf("LOT-MATCH"),
                fixFaceLocationIds = setOf(6131L),
            ),
        )

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(generalMatchLotUlId)
    }

    // ── Test 6: a partially-reserved storage stock unit is rejected ─────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6013"), Claim(key = "tenant_code", value = "ACME")])
    fun `a partially-reserved candidate on a non-picking location is rejected -- storage strictness`() {
        val itemDataId = 6013_001L
        val suffix = System.nanoTime()

        val ulId = createUnitLoad("UL-RESERVED-$suffix", 6141L)
        val stockId = createStock(ulId, itemDataId, 50.0)

        tenantContext.clientId = 6013L
        stockService.reserveStock(stockId, BigDecimal("10"), "test-reserve", tenantContext)

        val source = selector.selectSource(baseQuery(itemDataId, 6013L, targetLocationId = 6140L))

        assertThat(source).isNull()
    }

    // ── Test 7: a mixed unit load is rejected ────────────────────────────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6014"), Claim(key = "tenant_code", value = "ACME")])
    fun `a mixed unit load (a second live stock row for a different item) is rejected -- storage strictness`() {
        val itemDataId = 6014_001L
        val otherItemDataId = 6014_999L
        val suffix = System.nanoTime()

        val ulId = createUnitLoad("UL-MIXED-$suffix", 6151L)
        createStock(ulId, itemDataId, 40.0)
        createStock(ulId, otherItemDataId, 15.0) // second live stock row -> mixed UL

        val source = selector.selectSource(baseQuery(itemDataId, 6014L, targetLocationId = 6150L))

        assertThat(source).isNull()
    }

    // ── Test 8: picking-area candidate excluded unless fromPicking ──────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6015"), Claim(key = "tenant_code", value = "ACME")])
    fun `a candidate on a PICKING-usage location is excluded unless fromPicking is true`() {
        val ns = System.nanoTime().toString().takeLast(8)
        val itemDataId = 6015_001L

        val ltId = given().contentType(ContentType.JSON)
            .body("""{"name":"LT-RSS-PICK-$ns"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        val pickingAreaId = given().contentType(ContentType.JSON)
            .body("""{"name":"AREA-RSS-PICK-$ns","usages":["PICKING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        val pickingLocId = given().contentType(ContentType.JSON)
            .body("""{"name":"LOC-RSS-PICK-$ns","locationTypeId":$ltId,"areaId":$pickingAreaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")

        val pickingUlId = createUnitLoad("UL-RSS-PICKAREA-$ns", pickingLocId)
        createStock(pickingUlId, itemDataId, 25.0)

        val excludedByDefault = selector.selectSource(
            baseQuery(itemDataId, 6015L, targetLocationId = 6160L, fromPicking = false),
        )
        assertThat(excludedByDefault).isNull()

        val includedWhenAllowed = selector.selectSource(
            baseQuery(itemDataId, 6015L, targetLocationId = 6160L, fromPicking = true),
        )
        assertThat(includedWhenAllowed).isNotNull
        assertThat(includedWhenAllowed!!.unitLoadId).isEqualTo(pickingUlId)
    }

    /** Creates a location-type + PICKING-usage area + location; returns the location id. */
    private fun createPickingLocation(namePrefix: String): Long {
        val ns = System.nanoTime().toString().takeLast(8)
        val ltId = given().contentType(ContentType.JSON)
            .body("""{"name":"LT-$namePrefix-$ns"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201).extract().jsonPath().getLong("id")
        val pickingAreaId = given().contentType(ContentType.JSON)
            .body("""{"name":"AREA-$namePrefix-$ns","usages":["PICKING"]}""")
            .`when`().post("/api/v1/areas").then().statusCode(201).extract().jsonPath().getLong("id")
        return given().contentType(ContentType.JSON)
            .body("""{"name":"LOC-$namePrefix-$ns","locationTypeId":$ltId,"areaId":$pickingAreaId}""")
            .`when`().post("/api/v1/locations").then().statusCode(201).extract().jsonPath().getLong("id")
    }

    // ── Test 9 (IMPORTANT-2): lot preference falls back to the full pool when nothing matches ──

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6016"), Claim(key = "tenant_code", value = "ACME")])
    fun `a source is still returned when faceLotNumbers is non-empty but no candidate matches -- prefer-not-require`() {
        val itemDataId = 6016_001L
        val suffix = System.nanoTime()

        val ulId = createUnitLoad("UL-LOT-NOMATCH-$suffix", 6171L)
        createStock(ulId, itemDataId, 30.0, lotNumber = "LOT-DIFFERENT")

        val source = selector.selectSource(
            baseQuery(itemDataId, 6016L, targetLocationId = 6170L, faceLotNumbers = setOf("LOT-WANTED")),
        )

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(ulId)
    }

    // ── Test 10 (IMPORTANT-3): a fix-assigned PICK FACE does not get Phase-A priority ──────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6017"), Claim(key = "tenant_code", value = "ACME")])
    fun `a fix-assigned candidate that is itself a picking face does not get Phase-A priority over a FIFO-older general candidate`() {
        val itemDataId = 6017_001L
        val suffix = System.nanoTime()

        // Created FIRST -- FIFO-older -- plain general storage, NOT fix-assigned, NOT picking.
        val generalUlId = createUnitLoad("UL-GEN-OLD-$suffix", 6181L)
        createStock(generalUlId, itemDataId, 40.0)

        // Created SECOND -- FIFO-younger -- on a location that is BOTH fix-assigned (a normal
        // pick face IS a fix assignment) AND a PICKING-usage location.
        val pickFaceLocId = createPickingLocation("RSS-TIER")
        val pickFaceUlId = createUnitLoad("UL-PICKFACE-$suffix", pickFaceLocId)
        createStock(pickFaceUlId, itemDataId, 40.0)

        val source = selector.selectSource(
            baseQuery(
                itemDataId,
                6017L,
                targetLocationId = 6180L,
                fromPicking = true, // required to admit the picking-face candidate into the pool at all
                fixFaceLocationIds = setOf(pickFaceLocId),
            ),
        )

        // The picking-face candidate must NOT win via Phase-A priority (that would rob one pick
        // face to feed another). It's still eligible as an ordinary candidate, but loses on FIFO
        // to the older general-storage candidate once Phase A is (correctly) empty.
        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(generalUlId)
    }

    // ── Test 11 (R15/Task 4): destination-face strictness split ─────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6018"), Claim(key = "tenant_code", value = "ACME")])
    fun `a STORAGE target face rejects a partially-reserved picking-area candidate even with fromPicking true`() {
        val itemDataId = 6018_001L
        val suffix = System.nanoTime()

        val pickingLocId = createPickingLocation("RSS-STRICT-STORAGE")
        val ulId = createUnitLoad("UL-RSS-STRICT-STORAGE-$suffix", pickingLocId)
        val stockId = createStock(ulId, itemDataId, 50.0)

        tenantContext.clientId = 6018L
        stockService.reserveStock(stockId, BigDecimal("10"), "test-reserve", tenantContext)

        val source = selector.selectSource(
            baseQuery(
                itemDataId, 6018L, targetLocationId = 6190L,
                targetIsPickingFace = false, fromPicking = true,
            ),
        )

        assertThat(source).isNull()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6019"), Claim(key = "tenant_code", value = "ACME")])
    fun `a PICKING target face accepts the same partially-reserved picking-area candidate a STORAGE face would reject`() {
        val itemDataId = 6019_001L
        val suffix = System.nanoTime()

        val pickingLocId = createPickingLocation("RSS-STRICT-PICKING")
        val ulId = createUnitLoad("UL-RSS-STRICT-PICKING-$suffix", pickingLocId)
        val stockId = createStock(ulId, itemDataId, 50.0)

        tenantContext.clientId = 6019L
        stockService.reserveStock(stockId, BigDecimal("10"), "test-reserve", tenantContext)

        val source = selector.selectSource(
            baseQuery(
                itemDataId, 6019L, targetLocationId = 6191L,
                targetIsPickingFace = true, fromPicking = true,
            ),
        )

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(ulId)
    }

    // ── Test 12 (Row 3, defect-burndown-4 Task 5): excludeUnitLoadIds claim exclusion ────────

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6020"), Claim(key = "tenant_code", value = "ACME")])
    fun `excludeUnitLoadIds rejects an otherwise-eligible FIFO-older candidate, falling through to the next one`() {
        val itemDataId = 6020_001L
        val suffix = System.nanoTime()

        // Created FIRST -- FIFO-older -- would normally win, but is claimed elsewhere this pass.
        val claimedUlId = createUnitLoad("UL-RSS-CLAIMED-$suffix", 6201L)
        createStock(claimedUlId, itemDataId, 40.0)

        // Created SECOND -- FIFO-younger -- the only unclaimed eligible candidate.
        val freeUlId = createUnitLoad("UL-RSS-FREE-$suffix", 6202L)
        createStock(freeUlId, itemDataId, 40.0)

        val source = selector.selectSource(
            baseQuery(itemDataId, 6020L, targetLocationId = 6200L, excludeUnitLoadIds = setOf(claimedUlId)),
        )

        assertThat(source).isNotNull
        assertThat(source!!.unitLoadId).isEqualTo(freeUlId)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "6021"), Claim(key = "tenant_code", value = "ACME")])
    fun `excludeUnitLoadIds returns null when the only eligible candidate is already claimed`() {
        val itemDataId = 6021_001L
        val suffix = System.nanoTime()

        val onlyUlId = createUnitLoad("UL-RSS-ONLYCLAIMED-$suffix", 6211L)
        createStock(onlyUlId, itemDataId, 40.0)

        val source = selector.selectSource(
            baseQuery(itemDataId, 6021L, targetLocationId = 6210L, excludeUnitLoadIds = setOf(onlyUlId)),
        )

        assertThat(source).isNull()
    }
}

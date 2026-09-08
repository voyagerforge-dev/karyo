package com.karyo.layout.service

import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.layout.spi.AddToLocationRequest
import com.karyo.layout.spi.AddToLocationResult
import com.karyo.layout.spi.LocationFinder
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
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Integration test (REAL layout + inventory beans, real DB) for the LF10 consolidation search
 * mode, [LocationFinder.findAddToLocation], specified in
 * `docs/functional/location-finder.md#2a-the-add-to-location-search-mode-lf10`. Seeds
 * locations/fix-assignments via the layout REST API (mirrors
 * [LocationFinderFixExclusionTest]) and stock units via direct entity persistence (mirrors
 * [com.karyo.inventory.service.StockUnitLookupConsolidationTest]'s seedStock helper) -- REST is
 * bound to the fixed test principal (client 1), so arbitrary lot/veto/state fixtures need entity
 * persistence.
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy).
 */
@QuarkusTest
class AddToLocationTest {

    @Inject
    lateinit var locationFinder: LocationFinder

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    private val client = 1L

    private fun suffix() = System.nanoTime()

    // ── REST seeding helpers (mirrors LocationFinderFixExclusionTest) ──────

    private fun createArea(name: String, usages: List<String>): Long {
        val usagesJson = usages.joinToString(",") { "\"$it\"" }
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":[$usagesJson]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocation(name: String, typeId: Long, areaId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun lockLocation(id: Long, lockType: Int) {
        given().contentType(ContentType.JSON)
            .body("""{"lockType":$lockType}""")
            .`when`().post("/api/v1/locations/$id/lock")
            .then().statusCode(200)
    }

    private fun createFixAssignment(locationId: Long, itemDataId: Long, orderIndex: Int = 0): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId,"orderIndex":$orderIndex}""")
            .`when`().post("/api/v1/fix-assignments")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStrategy(name: String, body: String = "{}"): Long {
        val extra = if (body == "{}") "" else ",${body.trim('{', '}')}"
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$extra}""")
            .`when`().post("/api/v1/storage-strategies")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Item-unit + product creation -- a [com.karyo.layout.domain.model.FixAssignment] created
     * through REST validates its `itemDataId` against a real, ACTIVE product. */
    private fun createProductForFix(s: Long): Long {
        val itemSuffix = s.toString().takeLast(8)
        val itemUnitId = given().contentType(ContentType.JSON)
            .body("""{"name":"ATL-IU-$itemSuffix","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")
        return given().contentType(ContentType.JSON)
            .body("""{"number":"ATL-SKU-$itemSuffix","name":"AddToLocation Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    // ── Inventory-side direct seeding (mirrors StockUnitLookupConsolidationTest) ──

    @Transactional
    fun seedStock(
        locationId: Long,
        itemDataId: Long,
        amount: BigDecimal = BigDecimal.TEN,
        lotNumber: String? = null,
        strategyDate: Instant? = null,
    ): Long {
        val em = stockUnitRepository.getEntityManager()
        val ulType = em.find(UnitLoadType::class.java, 1L)
        val ul = UnitLoad().apply {
            labelId = "UL-ATL-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = "ATL-LOC-$locationId"
            this.clientId = client
        }
        em.persist(ul)
        val su = StockUnit().apply {
            this.clientId = client
            this.itemDataId = itemDataId
            itemDataNumber = "ATL-SKU"
            this.amount = amount
            unitLoad = ul
            this.state = 300 // ON_STOCK
            this.lockType = 0
            this.lotNumber = lotNumber
            this.strategyDate = strategyDate
        }
        em.persist(su)
        return su.id!!
    }

    private fun request(
        itemDataId: Long,
        lotNumber: String? = null,
        vetoStockUnitIds: Set<Long> = emptySet(),
        storageStrategyId: Long? = null,
    ) = AddToLocationRequest(
        clientId = client,
        itemDataId = itemDataId,
        lotNumber = lotNumber,
        vetoStockUnitIds = vetoStockUnitIds,
        storageStrategyId = storageStrategyId,
    )

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 1 - a fix assignment's picking location with matching-lot stock wins`() {
        val s = suffix()
        val picking = createArea("ATLA-$s", listOf("PICKING"))
        val type = createLocationType("ATLT-$s")
        val itemDataId = createProductForFix(s)

        val fixLoc = createLocation("ATL1-FIX-$s", type, picking)
        createFixAssignment(fixLoc, itemDataId)
        seedStock(fixLoc, itemDataId, lotNumber = "LOT-1")

        val result = locationFinder.findAddToLocation(request(itemDataId, lotNumber = "LOT-1"))

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(fixLoc)
        assertThat(found.viaFixAssignment).isTrue()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 1 - a lot-less request matches only lot-less stock at the fix location`() {
        val s = suffix()
        val picking = createArea("ATLH-$s", listOf("PICKING"))
        val type = createLocationType("ATLTH-$s")
        val itemDataId = createProductForFix(s)

        // A has the LOWER orderIndex -- it is genuinely considered first and rejected
        // (wrong-lot: null != "LOT-9"), not merely never reached. B is lot-less and qualifies.
        val lottedFix = createLocation("ATL8-LOTTED-$s", type, picking)
        val lotlessFix = createLocation("ATL8-LOTLESS-$s", type, picking)
        createFixAssignment(lottedFix, itemDataId, orderIndex = 0)
        createFixAssignment(lotlessFix, itemDataId, orderIndex = 1)
        seedStock(lottedFix, itemDataId, lotNumber = "LOT-9")
        seedStock(lotlessFix, itemDataId, lotNumber = null)

        val result = locationFinder.findAddToLocation(request(itemDataId, lotNumber = null))

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(lotlessFix)
        assertThat(found.viaFixAssignment).isTrue()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 1 - a locked fix location is skipped`() {
        val s = suffix()
        val picking = createArea("ATLB-$s", listOf("PICKING"))
        val type = createLocationType("ATLTB-$s")
        val itemDataId = createProductForFix(s)

        val lockedFix = createLocation("ATL2-LOCKED-$s", type, picking)
        val openFix = createLocation("ATL2-OPEN-$s", type, picking)
        createFixAssignment(lockedFix, itemDataId, orderIndex = 0)
        createFixAssignment(openFix, itemDataId, orderIndex = 1)
        lockLocation(lockedFix, 1)
        seedStock(openFix, itemDataId, lotNumber = "LOT-2")

        val result = locationFinder.findAddToLocation(request(itemDataId, lotNumber = "LOT-2"))

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(openFix)
        assertThat(found.viaFixAssignment).isTrue()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 1 - wrong-lot stock at the fix location disqualifies it`() {
        val s = suffix()
        val picking = createArea("ATLC-$s", listOf("PICKING"))
        val type = createLocationType("ATLTC-$s")
        val itemDataId = createProductForFix(s)

        val fixLoc = createLocation("ATL3-FIX-$s", type, picking)
        createFixAssignment(fixLoc, itemDataId)
        seedStock(fixLoc, itemDataId, lotNumber = "WRONG-LOT")

        // No other fix -- priority 2 fallback: a plain PICKING/unlocked location with
        // matching-lot stock.
        val fifoLoc = createLocation("ATL3-FIFO-$s", type, picking)
        val fifoStockId = seedStock(fifoLoc, itemDataId, lotNumber = "LOT-3")

        val result = locationFinder.findAddToLocation(request(itemDataId, lotNumber = "LOT-3"))

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(fifoLoc)
        assertThat(found.viaFixAssignment).isFalse()
        assertThat(fifoStockId).isNotNull() // sanity: the winning stock is the one we expect
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 1 - a vetoed stock unit at the fix location disqualifies it`() {
        val s = suffix()
        val picking = createArea("ATLD-$s", listOf("PICKING"))
        val type = createLocationType("ATLTD-$s")
        val itemDataId = createProductForFix(s)

        val fixLoc = createLocation("ATL4-FIX-$s", type, picking)
        createFixAssignment(fixLoc, itemDataId)
        val vetoedStockId = seedStock(fixLoc, itemDataId, lotNumber = "LOT-4")

        val fifoLoc = createLocation("ATL4-FIFO-$s", type, picking)
        seedStock(fifoLoc, itemDataId, lotNumber = "LOT-4")

        val result = locationFinder.findAddToLocation(
            request(itemDataId, lotNumber = "LOT-4", vetoStockUnitIds = setOf(vetoedStockId)),
        )

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(fifoLoc)
        assertThat(found.viaFixAssignment).isFalse()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 2 - FIFO-first pickable stock's location wins when no fix qualifies`() {
        val s = suffix()
        val picking = createArea("ATLE-$s", listOf("PICKING"))
        val type = createLocationType("ATLTE-$s")
        val itemDataId = 62_000_000L + (s % 1_000_000)
        val base = Instant.now().minus(10, ChronoUnit.DAYS)

        val earlier = createLocation("ATL5-EARLIER-$s", type, picking)
        val later = createLocation("ATL5-LATER-$s", type, picking)
        seedStock(earlier, itemDataId, lotNumber = "LOT-5", strategyDate = base)
        seedStock(later, itemDataId, lotNumber = "LOT-5", strategyDate = base.plus(5, ChronoUnit.DAYS))

        val result = locationFinder.findAddToLocation(request(itemDataId, lotNumber = "LOT-5"))

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(earlier)
        assertThat(found.viaFixAssignment).isFalse()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `priority 2 - vetoed stock is skipped and the next FIFO stock's location wins`() {
        val s = suffix()
        val picking = createArea("ATLF-$s", listOf("PICKING"))
        val type = createLocationType("ATLTF-$s")
        val itemDataId = 62_100_000L + (s % 1_000_000)
        val base = Instant.now().minus(10, ChronoUnit.DAYS)

        val earlier = createLocation("ATL6-EARLIER-$s", type, picking)
        val later = createLocation("ATL6-LATER-$s", type, picking)
        val earlierStockId = seedStock(earlier, itemDataId, lotNumber = "LOT-6", strategyDate = base)
        seedStock(later, itemDataId, lotNumber = "LOT-6", strategyDate = base.plus(5, ChronoUnit.DAYS))

        val result = locationFinder.findAddToLocation(
            request(itemDataId, lotNumber = "LOT-6", vetoStockUnitIds = setOf(earlierStockId)),
        )

        assertThat(result).isInstanceOf(AddToLocationResult.Found::class.java)
        val found = result as AddToLocationResult.Found
        assertThat(found.locationId).isEqualTo(later)
        assertThat(found.viaFixAssignment).isFalse()
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `manualSearch short-circuits to None before any query`() {
        val s = suffix()
        val picking = createArea("ATLG-$s", listOf("PICKING"))
        val type = createLocationType("ATLTG-$s")
        val itemDataId = createProductForFix(s)

        // A perfectly good fix-assignment consolidation target -- would be Found without
        // the flag (see the priority-1 win test above).
        val fixLoc = createLocation("ATL7-FIX-$s", type, picking)
        createFixAssignment(fixLoc, itemDataId)
        seedStock(fixLoc, itemDataId, lotNumber = "LOT-7")

        val strategyId = createStrategy("ATL-MANUAL-$s", """{"manualSearch":true}""")

        val result = locationFinder.findAddToLocation(
            request(itemDataId, lotNumber = "LOT-7", storageStrategyId = strategyId),
        )

        assertThat(result).isInstanceOf(AddToLocationResult.None::class.java)
        assertThat((result as AddToLocationResult.None).reason).contains("manual")
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `no qualifying stock anywhere returns None with a reason`() {
        val s = suffix()
        val itemDataId = 62_200_000L + (s % 1_000_000)

        val result = locationFinder.findAddToLocation(request(itemDataId, lotNumber = "LOT-8"))

        assertThat(result).isInstanceOf(AddToLocationResult.None::class.java)
        assertThat((result as AddToLocationResult.None).reason).contains(itemDataId.toString())
    }
}

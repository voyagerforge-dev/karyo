package com.karyo.layout.service

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
import java.time.Instant

/**
 * Integration test (REAL layout beans + DB) for the locations-layout sprint's Task 3: the
 * [LocationFinderService] StorageArea trio wiring — area restriction, `useAreaStrategyDate`
 * cross-area FIFO hiding, and `useItemDataArea` full-area hiding.
 *
 * A sibling of [LocationFinderTest] (not an addition to it) — kept as its own file, mirroring
 * [LocationFinderStrategyOwnershipTest]'s split, so neither file crosses Detekt's `LargeClass`
 * threshold. Duplicates a handful of small REST-seeding helpers from [LocationFinderTest]
 * rather than sharing them (both classes are `private`-scoped by design) — the sprint's
 * established pattern for this concern-split.
 *
 * client_id is fixed at 1 for all seeding/finder calls (silo tenancy), same as [LocationFinderTest].
 */
@QuarkusTest
class LocationFinderAreaTest {

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

    private fun createLocationType(name: String, liftingCapacity: BigDecimal?): Long {
        val cap = liftingCapacity?.let { ""","liftingCapacity":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name"$cap}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    @Suppress("LongParameterList")
    private fun createLocation(name: String, typeId: Long, areaId: Long, zoneId: Long? = null, clusterId: Long? = null): Long {
        val zonePart = zoneId?.let { ""","zoneId":$it""" } ?: ""
        val clusterPart = clusterId?.let { ""","locationClusterId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$zonePart$clusterPart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Directly set base allocation (no REST setter) — simulates an occupied location, mirrors
     * [LocationFinderTest.setAllocation]. */
    @Transactional
    fun setAllocation(id: Long, allocation: BigDecimal) {
        val loc = reservationRepository.getEntityManager()
            .find(com.karyo.layout.domain.model.StorageLocation::class.java, id)
        loc.allocation = allocation
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

    /** Rewrites the strategy's ordered area list (orderIndex 1..N, per input order). */
    private fun putStrategyAreas(strategyId: Long, orderedAreaIds: List<Long>) {
        val ids = orderedAreaIds.joinToString(",")
        given().contentType(ContentType.JSON)
            .body("[$ids]")
            .`when`().put("/api/v1/storage-strategies/$strategyId/areas")
            .then().statusCode(200)
    }

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Bounded suffix for item-unit names (`@Size(max = 20)`) — a full `System.nanoTime()`
     * (15+ digits on a long-running host) overflows a short prefix + full suffix past 20
     * chars; mirrors `FixAssignmentLookupTest`'s identical workaround. */
    private fun shortSuffix(s: Long) = s.toString().takeLast(8)

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"Area Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Suppress("LongParameterList")
    private fun createItemDataArea(
        itemDataId: Long,
        storageAreaId: Long,
        plannedAmount: BigDecimal? = null,
        plannedStocks: Int? = null,
    ): Long {
        val amountPart = plannedAmount?.let { ""","plannedAmount":$it""" } ?: ""
        val stocksPart = plannedStocks?.let { ""","plannedStocks":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"storageAreaId":$storageAreaId$amountPart$stocksPart}""")
            .`when`().post("/api/v1/item-data-areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /**
     * Direct entity persistence of a UnitLoad + ON_STOCK StockUnit at [locationId] — bypasses
     * REST/tenant-write-scope checks entirely (mirrors [LocationFinderTest.setAllocation] /
     * [LocationFinderStrategyOwnershipTest]'s `createForeignStrategy` direct-entity pattern)
     * so [clientId] can be a foreign tenant for the isolation test, and [strategyDate] can be
     * set to an arbitrary FIFO date without depending on `bestBefore` derivation. `unitLoadTypeId`
     * 1 is the fixture type every other test in this module already relies on existing.
     */
    @Suppress("LongParameterList")
    @Transactional
    fun seedStock(
        locationId: Long,
        locationName: String,
        itemDataId: Long,
        amount: BigDecimal,
        strategyDate: Instant,
        clientId: Long = client,
    ): Long {
        val em = reservationRepository.getEntityManager()
        val ulType = em.find(com.karyo.inventory.domain.model.UnitLoadType::class.java, 1L)
        val ul = com.karyo.inventory.domain.model.UnitLoad().apply {
            labelId = "UL-OCC-${System.nanoTime()}"
            unitLoadType = ulType
            storageLocationId = locationId
            storageLocationName = locationName
            this.clientId = clientId
        }
        em.persist(ul)
        val su = com.karyo.inventory.domain.model.StockUnit().apply {
            this.clientId = clientId
            this.itemDataId = itemDataId
            itemDataNumber = "OCC-SKU"
            this.amount = amount
            unitLoad = ul
            state = 300
            this.strategyDate = strategyDate
        }
        em.persist(su)
        return su.id!!
    }

    @Suppress("LongParameterList")
    private fun request(
        reservationKey: Long,
        weight: BigDecimal = BigDecimal.ZERO,
        clientId: Long? = 1L,
        preferredZoneId: Long? = null,
        storageStrategyId: Long? = null,
        itemDataId: Long? = null,
        strategyDate: Instant? = null,
    ) = LocationFinderRequest(
        unitLoadId = 9000L + reservationKey,
        unitLoadTypeId = 1L,
        weight = weight,
        clientId = clientId,
        reservationKey = reservationKey,
        preferredZoneId = preferredZoneId,
        storageStrategyId = storageStrategyId,
        itemDataId = itemDataId,
        strategyDate = strategyDate,
    )

    private fun suffix() = System.nanoTime()

    // ── (a) area restriction ────────────────────────────────────────────

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `area restriction excludes an out-of-area candidate and finds the in-area one`() {
        val s = suffix()
        val storage = createArea("AR1-ST-$s", listOf("STORAGE"))
        val zone = createZone("AR1-Z-$s")
        val type = createLocationType("AR1-LT-$s", BigDecimal("1000"))
        val clusterIn = createLocationCluster("AR1-CIN-$s")
        val clusterOut = createLocationCluster("AR1-COUT-$s")
        val storageArea = createStorageArea("AR1-SA-$s", listOf(clusterIn))

        val inLoc = createLocation("AR1-IN-$s", type, storage, zoneId = zone, clusterId = clusterIn)
        createLocation("AR1-OUT-$s", type, storage, zoneId = zone, clusterId = clusterOut)

        val strategyId = createStrategy("AR1-STRAT-$s")
        putStrategyAreas(strategyId, listOf(storageArea))

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(inLoc)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `no areas configured on the strategy - no restriction whatsoever, identical to pre-task result`() {
        val s = suffix()
        val storage = createArea("AR2-ST-$s", listOf("STORAGE"))
        val zone = createZone("AR2-Z-$s")
        val type = createLocationType("AR2-LT-$s", BigDecimal("1000"))
        val cluster = createLocationCluster("AR2-C-$s")

        // Locations carry a locationCluster, but the strategy has NO configured areas at
        // all -- clusters must be entirely irrelevant to the outcome (regression pin).
        val empty = createLocation("AR2-EMPTY-$s", type, storage, zoneId = zone, clusterId = cluster)
        val half = createLocation("AR2-HALF-$s", type, storage, zoneId = zone, clusterId = cluster)
        setAllocation(half, BigDecimal("50"))

        val strategyId = createStrategy("AR2-STRAT-$s") // no PUT .../areas call at all

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(empty)
    }

    // ── (b) useAreaStrategyDate: cross-area FIFO hiding ─────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `older same-product stock in area A hides area B - cross-area FIFO`() {
        val s = suffix()
        val storage = createArea("FIFO-ST-$s", listOf("STORAGE"))
        val zone = createZone("FIFO-Z-$s")
        val type = createLocationType("FIFO-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FIFO-CA-$s")
        val clusterB = createLocationCluster("FIFO-CB-$s")
        val areaA = createStorageArea("FIFO-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("FIFO-AB-$s", listOf(clusterB))

        val locAOldName = "FIFO-A-OLD-$s"
        val locAOld = createLocation(locAOldName, type, storage, zoneId = zone, clusterId = clusterA)
        setAllocation(locAOld, BigDecimal("100")) // already-occupied pallet slot, not a candidate itself
        val locAEmpty = createLocation("FIFO-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)
        createLocation("FIFO-B-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterB)

        val itemUnitId = createItemUnit("FIFO-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("FIFO-SKU-$s", itemUnitId)
        seedStock(locAOld, locAOldName, itemDataId, BigDecimal.TEN, Instant.parse("2020-01-01T00:00:00Z"))

        val strategyId = createStrategy("FIFO-STRAT-$s", """{"useAreaStrategyDate":true}""")
        putStrategyAreas(strategyId, listOf(areaA, areaB)) // A first, B second

        tenantContext.clientId = client // direct SPI call bypasses TenantFilter; occupancy reads need it set
        val result = locationFinder.findPutawayLocation(
            request(
                reservationKey = s,
                preferredZoneId = zone,
                storageStrategyId = strategyId,
                itemDataId = itemDataId,
                strategyDate = Instant.now(),
            )
        )
        // Area B (after area A, which already holds older same-product stock) is hidden --
        // only area A's own empty candidate survives. If B were NOT hidden, it would still be
        // a live alternative deprioritized by area-order, not gone -- asserting the exact id
        // proves B is excluded, not merely deprioritized.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locAEmpty)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `single-area strategy - useAreaStrategyDate active but no hiding possible`() {
        val s = suffix()
        val storage = createArea("FIFO1-ST-$s", listOf("STORAGE"))
        val zone = createZone("FIFO1-Z-$s")
        val type = createLocationType("FIFO1-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FIFO1-CA-$s")
        val areaA = createStorageArea("FIFO1-AA-$s", listOf(clusterA))

        val locAOldName = "FIFO1-A-OLD-$s"
        val locAOld = createLocation(locAOldName, type, storage, zoneId = zone, clusterId = clusterA)
        setAllocation(locAOld, BigDecimal("100"))
        val locAEmpty = createLocation("FIFO1-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)

        val itemUnitId = createItemUnit("FIFO1-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("FIFO1-SKU-$s", itemUnitId)
        seedStock(locAOld, locAOldName, itemDataId, BigDecimal.TEN, Instant.parse("2020-01-01T00:00:00Z"))

        val strategyId = createStrategy("FIFO1-STRAT-$s", """{"useAreaStrategyDate":true}""")
        putStrategyAreas(strategyId, listOf(areaA)) // ONE area only -- nothing "after" it to hide

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(
                reservationKey = s,
                preferredZoneId = zone,
                storageStrategyId = strategyId,
                itemDataId = itemDataId,
                strategyDate = Instant.now(),
            )
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locAEmpty)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `older stock of a DIFFERENT product does not trigger FIFO hiding`() {
        val s = suffix()
        val storage = createArea("FIFOD-ST-$s", listOf("STORAGE"))
        val zone = createZone("FIFOD-Z-$s")
        val type = createLocationType("FIFOD-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FIFOD-CA-$s")
        val clusterB = createLocationCluster("FIFOD-CB-$s")
        val areaA = createStorageArea("FIFOD-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("FIFOD-AB-$s", listOf(clusterB))

        val locAOldName = "FIFOD-A-OLD-$s"
        // Area A's only location is fully allocated -- it cannot itself be chosen, so a
        // Found result can ONLY come from area B, proving B was not (wrongly) hidden.
        val locAOld = createLocation(locAOldName, type, storage, zoneId = zone, clusterId = clusterA)
        setAllocation(locAOld, BigDecimal("100"))
        val locBEmpty = createLocation("FIFOD-B-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterB)

        val itemUnitId = createItemUnit("FIFOD-IU-${shortSuffix(s)}")
        val otherItemUnitId = createItemUnit("FIFOD-IU2-${shortSuffix(s)}")
        val itemDataId = createProduct("FIFOD-SKU-$s", itemUnitId)
        val otherItemDataId = createProduct("FIFOD-SKU2-$s", otherItemUnitId)
        // Older stock in area A, but of a DIFFERENT product than the incoming one.
        seedStock(locAOld, locAOldName, otherItemDataId, BigDecimal.TEN, Instant.parse("2020-01-01T00:00:00Z"))

        val strategyId = createStrategy("FIFOD-STRAT-$s", """{"useAreaStrategyDate":true}""")
        putStrategyAreas(strategyId, listOf(areaA, areaB))

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(
                reservationKey = s,
                preferredZoneId = zone,
                storageStrategyId = strategyId,
                itemDataId = itemDataId,
                strategyDate = Instant.now(),
            )
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locBEmpty)
    }

    // ── (c) useItemDataArea: full-area hiding ───────────────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `full area by plannedStocks threshold is hidden entirely`() {
        val s = suffix()
        val storage = createArea("FULLS-ST-$s", listOf("STORAGE"))
        val zone = createZone("FULLS-Z-$s")
        val type = createLocationType("FULLS-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FULLS-CA-$s")
        val clusterB = createLocationCluster("FULLS-CB-$s")
        val areaA = createStorageArea("FULLS-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("FULLS-AB-$s", listOf(clusterB))

        val locAOccupiedName = "FULLS-A-OCC-$s"
        val locAOccupied = createLocation(locAOccupiedName, type, storage, zoneId = zone, clusterId = clusterA)
        createLocation("FULLS-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)
        val locBEmpty = createLocation("FULLS-B-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterB)

        val itemUnitId = createItemUnit("FULLS-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("FULLS-SKU-$s", itemUnitId)
        // One unit load already on hand in area A -> plannedStocks=1 threshold MET.
        seedStock(locAOccupied, locAOccupiedName, itemDataId, BigDecimal.ONE, Instant.now())
        createItemDataArea(itemDataId, areaA, plannedStocks = 1)

        val strategyId = createStrategy("FULLS-STRAT-$s", """{"useItemDataArea":true}""")
        putStrategyAreas(strategyId, listOf(areaA, areaB))

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = itemDataId)
        )
        // Area A is FULL and hidden ENTIRELY -- both locAOccupied and locAEmpty vanish, so
        // the only possible Found is in area B.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locBEmpty)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `full area by plannedAmount threshold is hidden entirely`() {
        val s = suffix()
        val storage = createArea("FULLA-ST-$s", listOf("STORAGE"))
        val zone = createZone("FULLA-Z-$s")
        val type = createLocationType("FULLA-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FULLA-CA-$s")
        val clusterB = createLocationCluster("FULLA-CB-$s")
        val areaA = createStorageArea("FULLA-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("FULLA-AB-$s", listOf(clusterB))

        val locAOccupiedName = "FULLA-A-OCC-$s"
        val locAOccupied = createLocation(locAOccupiedName, type, storage, zoneId = zone, clusterId = clusterA)
        createLocation("FULLA-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)
        val locBEmpty = createLocation("FULLA-B-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterB)

        val itemUnitId = createItemUnit("FULLA-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("FULLA-SKU-$s", itemUnitId)
        // Amount already on hand (10) meets plannedAmount (10) -- plannedStocks left undefined.
        seedStock(locAOccupied, locAOccupiedName, itemDataId, BigDecimal.TEN, Instant.now())
        createItemDataArea(itemDataId, areaA, plannedAmount = BigDecimal.TEN)

        val strategyId = createStrategy("FULLA-STRAT-$s", """{"useItemDataArea":true}""")
        putStrategyAreas(strategyId, listOf(areaA, areaB))

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = itemDataId)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locBEmpty)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `only one of two defined thresholds met - area is NOT hidden (every-threshold rule)`() {
        val s = suffix()
        val storage = createArea("FULLM-ST-$s", listOf("STORAGE"))
        val zone = createZone("FULLM-Z-$s")
        val type = createLocationType("FULLM-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FULLM-CA-$s")
        val areaA = createStorageArea("FULLM-AA-$s", listOf(clusterA))

        val locAOccupiedName = "FULLM-A-OCC-$s"
        val locAOccupied = createLocation(locAOccupiedName, type, storage, zoneId = zone, clusterId = clusterA)
        setAllocation(locAOccupied, BigDecimal("100")) // not itself a candidate
        val locAEmpty = createLocation("FULLM-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)

        val itemUnitId = createItemUnit("FULLM-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("FULLM-SKU-$s", itemUnitId)
        // 1 unit load on hand (amount 10): plannedAmount=10 MET, plannedStocks=5 NOT met
        // (only 1 UL present) -- only ONE of two defined thresholds -> not full.
        seedStock(locAOccupied, locAOccupiedName, itemDataId, BigDecimal.TEN, Instant.now())
        createItemDataArea(itemDataId, areaA, plannedAmount = BigDecimal.TEN, plannedStocks = 5)

        val strategyId = createStrategy("FULLM-STRAT-$s", """{"useItemDataArea":true}""")
        putStrategyAreas(strategyId, listOf(areaA))

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = itemDataId)
        )
        // If area A were (incorrectly) hidden, this would be NoLocation -- it is the ONLY
        // configured area. Found proves the every-threshold rule held.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locAEmpty)
    }

    @Test
    @TestSecurity(user = "manager", roles = ["layout-read", "layout-write", "MANAGER"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `no ItemDataArea row for the product - area is not full, not hidden`() {
        val s = suffix()
        val storage = createArea("FULLN-ST-$s", listOf("STORAGE"))
        val zone = createZone("FULLN-Z-$s")
        val type = createLocationType("FULLN-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FULLN-CA-$s")
        val areaA = createStorageArea("FULLN-AA-$s", listOf(clusterA))

        val locAEmpty = createLocation("FULLN-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)

        // useItemDataArea=true, area configured, but NO ItemDataArea row created at all for
        // this (or any) product -- "not tracked" must never be treated as "full".
        val strategyId = createStrategy("FULLN-STRAT-$s", """{"useItemDataArea":true}""")
        putStrategyAreas(strategyId, listOf(areaA))

        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = 999_888_777L)
        )
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locAEmpty)
    }

    // ── flags-off regression + tenant isolation ─────────────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `flags-off regression - areas configured but both flags false - restriction only, no hiding`() {
        val s = suffix()
        val storage = createArea("FLAGSOFF-ST-$s", listOf("STORAGE"))
        val zone = createZone("FLAGSOFF-Z-$s")
        val type = createLocationType("FLAGSOFF-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("FLAGSOFF-CA-$s")
        val clusterB = createLocationCluster("FLAGSOFF-CB-$s")
        val areaA = createStorageArea("FLAGSOFF-AA-$s", listOf(clusterA))
        val areaB = createStorageArea("FLAGSOFF-AB-$s", listOf(clusterB))

        // Area A holds older same-product stock (would trigger FIFO hiding of B) AND has an
        // ItemDataArea row whose threshold is already met (would trigger full-hiding of A
        // itself) -- both flags are false below: only area restriction should apply.
        val locAOccName = "FLAGSOFF-A-OCC-$s"
        val locAOcc = createLocation(locAOccName, type, storage, zoneId = zone, clusterId = clusterA)
        val locAEmpty = createLocation("FLAGSOFF-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)
        val locBPartial = createLocation("FLAGSOFF-B-PARTIAL-$s", type, storage, zoneId = zone, clusterId = clusterB)
        setAllocation(locAOcc, BigDecimal("100"))
        setAllocation(locBPartial, BigDecimal("50")) // emptier than 100 but not as empty as locAEmpty (0)

        val itemUnitId = createItemUnit("FLAGSOFF-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("FLAGSOFF-SKU-$s", itemUnitId)
        seedStock(locAOcc, locAOccName, itemDataId, BigDecimal.TEN, Instant.parse("2020-01-01T00:00:00Z"))
        createItemDataArea(itemDataId, areaA, plannedStocks = 1)

        val strategyId = createStrategy("FLAGSOFF-STRAT-$s", """{"useAreaStrategyDate":false,"useItemDataArea":false}""")
        putStrategyAreas(strategyId, listOf(areaA, areaB))

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(
                reservationKey = s,
                preferredZoneId = zone,
                storageStrategyId = strategyId,
                itemDataId = itemDataId,
                strategyDate = Instant.now(),
            )
        )
        // Default allocation/name ordering (not area order) governs since useAreaStrategyDate
        // is off: locAEmpty (allocation 0) beats locBPartial (allocation 50). Its mere
        // presence as a live candidate (not hidden) also proves useItemDataArea's met
        // threshold was ignored.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat((result as LocationFinderResult.Found).locationId).isEqualTo(locAEmpty)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["layout-read", "layout-write", "MANAGER", "product-read", "product-write", "inventory-read", "inventory-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `tenant isolation - a foreign tenant's occupancy does not hide an area`() {
        val s = suffix()
        val storage = createArea("TENISO-ST-$s", listOf("STORAGE"))
        val zone = createZone("TENISO-Z-$s")
        val type = createLocationType("TENISO-LT-$s", BigDecimal("1000"))
        val clusterA = createLocationCluster("TENISO-CA-$s")
        val areaA = createStorageArea("TENISO-AA-$s", listOf(clusterA))

        val locForeignName = "TENISO-A-FOREIGN-$s"
        val locForeign = createLocation(locForeignName, type, storage, zoneId = zone, clusterId = clusterA)
        val locOwnEmpty = createLocation("TENISO-A-EMPTY-$s", type, storage, zoneId = zone, clusterId = clusterA)

        val itemUnitId = createItemUnit("TENISO-IU-${shortSuffix(s)}")
        val itemDataId = createProduct("TENISO-SKU-$s", itemUnitId)
        // A DIFFERENT tenant's stock -- would meet the plannedStocks=1 threshold if
        // (wrongly) counted for client 1's read.
        val foreignClientId = s
        seedStock(locForeign, locForeignName, itemDataId, BigDecimal.ONE, Instant.now(), clientId = foreignClientId)
        createItemDataArea(itemDataId, areaA, plannedStocks = 1)

        val strategyId = createStrategy("TENISO-STRAT-$s", """{"useItemDataArea":true}""")
        putStrategyAreas(strategyId, listOf(areaA))

        tenantContext.clientId = client
        val result = locationFinder.findPutawayLocation(
            request(reservationKey = s, preferredZoneId = zone, storageStrategyId = strategyId, itemDataId = itemDataId)
        )
        // client 1's read sees ZERO of its own occupancy in area A (the only stock there
        // belongs to a foreign tenant) -- 0 < 1, so area A is NOT full, NOT hidden.
        assertThat(result).isInstanceOf(LocationFinderResult.Found::class.java)
        assertThat(setOf(locForeign, locOwnEmpty)).contains((result as LocationFinderResult.Found).locationId)
    }
}

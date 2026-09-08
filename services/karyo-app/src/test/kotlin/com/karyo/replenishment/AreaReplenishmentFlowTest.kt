package com.karyo.replenishment

import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.tasks.service.TaskService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Real-bean, end-to-end coverage for [ReplenishmentService.scanAreas] (R12b, replenishment
 * sprint Task 6) — the area-level (Mode 2) counterpart to [ReplenishmentTopUpFlowTest]'s
 * fix-face (Mode 1) coverage. Seeding mirrors [com.karyo.layout.ItemDataAreaLookupTest]
 * (item-unit -> product -> cluster -> storage-area -> item-data-area) plus
 * [com.karyo.inventory.StockSummaryLookupTest]/[com.karyo.inventory.ReplenishmentSourceSelectorTest]'s
 * unit-load/stock REST helpers. Each test uses a dedicated `clientId` (9301-9307) so no fixture
 * bleeds across tests on the shared test database.
 *
 * `scanAreas` itself is private — all assertions go through the public [ReplenishmentService.scan]
 * entry point, exactly as the scheduler and REST resource call it.
 */
@QuarkusTest
class AreaReplenishmentFlowTest {

    @Inject
    lateinit var replenishmentService: ReplenishmentService

    @Inject
    lateinit var taskService: TaskService

    @Inject
    lateinit var entityManager: EntityManager

    private fun ns() = System.nanoTime().toString().takeLast(9)

    /** Directly set base allocation (no REST setter) -- mirrors `LocationFinderCapacityTest.setAllocation`. */
    @Transactional
    fun setAllocation(locationId: Long, allocation: BigDecimal) {
        val loc = entityManager.find(com.karyo.layout.domain.model.StorageLocation::class.java, locationId)
        loc.allocation = allocation
    }

    /** Directly persist a live [com.karyo.layout.domain.model.LocationReservation] (no REST
     *  creator) -- mirrors `TransferChainFlowTest`'s direct-entityManager reservation fixture.
     *  `transportOrderId` is an unrelated placeholder: [occupiedLocationIds][
     *  com.karyo.layout.spi.LocationLockPort.occupiedLocationIds]'s reservation-load read is keyed
     *  by `locationId` + `expiresAt`, never `transportOrderId`. */
    @Transactional
    fun createReservation(locationId: Long, percent: BigDecimal, expiresAt: Instant) {
        val reservation = com.karyo.layout.domain.model.LocationReservation().apply {
            this.locationId = locationId
            this.transportOrderId = 0
            this.percent = percent
            this.expiresAt = expiresAt
        }
        entityManager.persist(reservation)
    }

    private fun createFixAssignment(locationId: Long, itemDataId: Long, minAmount: Int): Long =
        given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId,"minAmount":$minAmount}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    // ── Seeding helpers (mirror ItemDataAreaLookupTest / StockSummaryLookupTest) ────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"ARF Test Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStorageArea(name: String, clusterIds: List<Long>): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":${clusterIds}}""")
            .`when`().post("/api/v1/storage-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLayoutArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, layoutAreaId: Long, clusterId: Long? = null): Long {
        val clusterPart = clusterId?.let { ""","locationClusterId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$layoutAreaId$clusterPart}""")
            .`when`().post("/api/v1/locations").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createItemDataArea(itemDataId: Long, storageAreaId: Long, plannedAmount: BigDecimal?, plannedStocks: Int?): Long {
        val amountPart = plannedAmount?.let { ""","plannedAmount":$it""" } ?: ""
        val stocksPart = plannedStocks?.let { ""","plannedStocks":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"itemDataId":$itemDataId,"storageAreaId":$storageAreaId$amountPart$stocksPart}""")
            .`when`().post("/api/v1/item-data-areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}""",
            )
            .`when`().post("/api/v1/unit-loads").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createStock(ulId: Long, itemDataId: Long, itemNumber: String, amount: BigDecimal): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private val roles = listOf("layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write")

    private data class AreaSeed(val itemDataAreaId: Long, val itemDataId: Long, val itemNumber: String, val areaLocationId: Long)

    /** A single-cluster, single-location area, with a matching product but NO stock at all yet. */
    private fun seedEmptyArea(s: String, plannedAmount: BigDecimal?, plannedStocks: Int?): AreaSeed {
        val itemUnitId = createItemUnit("IU-ARF-$s")
        val itemNumber = "ARF-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)
        val clusterId = createCluster("ARF-CLU-$s")
        val storageAreaId = createStorageArea("ARF-SA-$s", listOf(clusterId))
        val ltId = createLocationType("ARF-LT-$s")
        val layoutAreaId = createLayoutArea("ARF-LAREA-$s")
        val areaLocationId = createLocation("ARF-LOC-$s", ltId, layoutAreaId, clusterId)
        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, plannedAmount, plannedStocks)
        return AreaSeed(itemDataAreaId, itemDataId, itemNumber, areaLocationId)
    }

    /** A genuinely OUTSIDE-the-area unit load carrying enough stock to be a valid replenishment source. */
    private fun seedOutsideSource(s: String, itemDataId: Long, itemNumber: String, amount: BigDecimal): Long {
        val ltId = createLocationType("ARF-SRC-LT-$s")
        val layoutAreaId = createLayoutArea("ARF-SRC-LAREA-$s")
        val srcLocationId = createLocation("ARF-SRC-LOC-$s", ltId, layoutAreaId) // no cluster -> outside every area
        val ulId = createUnitLoad("UL-ARF-SRC-$s", srcLocationId)
        createStock(ulId, itemDataId, itemNumber, amount)
        return ulId
    }

    // ── (a) below plannedAmount -> one order, itemDataAreaId set, destination inside the area ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9301"), Claim(key = "tenant_code", value = "ACME")])
    fun `an area below plannedAmount mints one REPLENISH order with itemDataAreaId and an in-area destination`() {
        val s = ns()
        val seed = seedEmptyArea(s, plannedAmount = BigDecimal("50"), plannedStocks = null)
        val sourceUlId = seedOutsideSource(s, seed.itemDataId, seed.itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9301L)

        val task = result.generated.single { it.itemDataAreaId == seed.itemDataAreaId }
        assertThat(task.unitLoadId).isEqualTo(sourceUlId)
        assertThat(task.itemDataNumber).isEqualTo(seed.itemNumber)

        val order = taskService.findById(task.taskId, 9301L)
        assertThat(order.transportType).isEqualTo("REPLENISH")
        assertThat(order.destinationLocationId).isEqualTo(seed.areaLocationId)
        assertThat(order.unitLoadId).isEqualTo(sourceUlId)
    }

    // ── (b) below plannedStocks only -> likewise ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9302"), Claim(key = "tenant_code", value = "ACME")])
    fun `an area below plannedStocks only also mints one REPLENISH order`() {
        val s = ns()
        val seed = seedEmptyArea(s, plannedAmount = null, plannedStocks = 2)
        val sourceUlId = seedOutsideSource(s, seed.itemDataId, seed.itemNumber, BigDecimal("30"))

        val result = replenishmentService.scan(9302L)

        val task = result.generated.single { it.itemDataAreaId == seed.itemDataAreaId }
        assertThat(task.unitLoadId).isEqualTo(sourceUlId)
    }

    // ── (c) a met area mints nothing ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9303"), Claim(key = "tenant_code", value = "ACME")])
    fun `a met area mints nothing`() {
        val s = ns()
        val seed = seedEmptyArea(s, plannedAmount = BigDecimal("10"), plannedStocks = 1)
        // Stock the area's OWN location above both thresholds -- no deficiency.
        val ulInArea = createUnitLoad("UL-ARF-MET-$s", seed.areaLocationId)
        createStock(ulInArea, seed.itemDataId, seed.itemNumber, BigDecimal("50"))
        // A source elsewhere too, so a false-positive generation would be visible in the assertion.
        seedOutsideSource(s, seed.itemDataId, seed.itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9303L)

        assertThat(result.generated.map { it.itemDataAreaId }).doesNotContain(seed.itemDataAreaId)
        assertThat(result.shortfalls.map { it.itemDataAreaId }).doesNotContain(seed.itemDataAreaId)
    }

    // ── (d) an area with an open replenishment is skipped (dedupe) ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9304"), Claim(key = "tenant_code", value = "ACME")])
    fun `an area with an already-open area replenishment is skipped on the next scan`() {
        val s = ns()
        val seed = seedEmptyArea(s, plannedAmount = BigDecimal("50"), plannedStocks = null)
        seedOutsideSource(s, seed.itemDataId, seed.itemNumber, BigDecimal("100"))

        val first = replenishmentService.scan(9304L)
        assertThat(first.generated.map { it.itemDataAreaId }).contains(seed.itemDataAreaId)

        // Second pass: the first order is still open (RELEASED) -- dedupe must skip the area.
        val second = replenishmentService.scan(9304L)
        assertThat(second.generated.map { it.itemDataAreaId }).doesNotContain(seed.itemDataAreaId)
    }

    // ── (e) a candidate INSIDE the area is not selected as a source ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9305"), Claim(key = "tenant_code", value = "ACME")])
    fun `a source candidate inside the deficient area's own cluster is not selected`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-ARF-EXC-$s")
        val itemNumber = "ARF-SKU-EXC-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)
        val clusterId = createCluster("ARF-CLU-EXC-$s")
        val storageAreaId = createStorageArea("ARF-SA-EXC-$s", listOf(clusterId))
        val ltId = createLocationType("ARF-LT-EXC-$s")
        val layoutAreaId = createLayoutArea("ARF-LAREA-EXC-$s")
        // TWO locations in the area's cluster: destLoc (empty) and inAreaLoc (holds a little stock).
        val destLoc = createLocation("ARF-LOC-DEST-$s", ltId, layoutAreaId, clusterId)
        val inAreaLoc = createLocation("ARF-LOC-INAREA-$s", ltId, layoutAreaId, clusterId)
        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, BigDecimal("50"), null)

        // In-area candidate: real ON_STOCK stock, but sitting INSIDE the deficient area itself.
        val inAreaUlId = createUnitLoad("UL-ARF-INAREA-$s", inAreaLoc)
        createStock(inAreaUlId, itemDataId, itemNumber, BigDecimal("5")) // counts toward the area's own sum too, still < 50

        // Genuine outside source, large enough to be the only valid pick.
        val outsideUlId = seedOutsideSource(s, itemDataId, itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9305L)

        val task = result.generated.single { it.itemDataAreaId == itemDataAreaId }
        assertThat(task.unitLoadId).isEqualTo(outsideUlId)
        assertThat(task.unitLoadId).isNotEqualTo(inAreaUlId)

        val order = taskService.findById(task.taskId, 9305L)
        assertThat(order.destinationLocationId).isIn(destLoc, inAreaLoc)
    }

    // ── (f) tenant isolation ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9306"), Claim(key = "tenant_code", value = "ACME")])
    fun `a different tenant's scan never sees this tenant's deficient area`() {
        val s = ns()
        val seed = seedEmptyArea(s, plannedAmount = BigDecimal("50"), plannedStocks = null)
        seedOutsideSource(s, seed.itemDataId, seed.itemNumber, BigDecimal("100"))

        val otherTenantResult = replenishmentService.scan(9307L)

        assertThat(otherTenantResult.generated.map { it.itemDataAreaId }).doesNotContain(seed.itemDataAreaId)
    }

    // ── (g) Row 4 (defect-burndown-4, Task 5): occupancy-aware destination -- full allocation ──

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9310"), Claim(key = "tenant_code", value = "ACME")])
    fun `an area destination skips a lowest-id location whose allocation is already full`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-OCC-A-$s")
        val itemNumber = "OCC-A-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)
        val clusterId = createCluster("OCC-A-CLU-$s")
        val storageAreaId = createStorageArea("OCC-A-SA-$s", listOf(clusterId))
        val ltId = createLocationType("OCC-A-LT-$s")
        val layoutAreaId = createLayoutArea("OCC-A-LAREA-$s")
        // Created FIRST -> lowest id -> the naive pre-fix pick.
        val fullLoc = createLocation("OCC-A-LOC-FULL-$s", ltId, layoutAreaId, clusterId)
        // Created SECOND -> higher id -> must be chosen once occupancy is honored.
        val freeLoc = createLocation("OCC-A-LOC-FREE-$s", ltId, layoutAreaId, clusterId)
        check(fullLoc < freeLoc) { "fixture assumption: fullLoc must sort before freeLoc" }
        setAllocation(fullLoc, BigDecimal("100"))
        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, BigDecimal("50"), null)
        seedOutsideSource(s, itemDataId, itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9310L)

        val task = result.generated.single { it.itemDataAreaId == itemDataAreaId }
        val order = taskService.findById(task.taskId, 9310L)
        assertThat(order.destinationLocationId).isEqualTo(freeLoc)
        assertThat(order.destinationLocationId).isNotEqualTo(fullLoc)
    }

    // ── (h) Row 4: occupancy-aware destination -- live reservation ────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9311"), Claim(key = "tenant_code", value = "ACME")])
    fun `an area destination skips a lowest-id location holding a live reservation`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-OCC-B-$s")
        val itemNumber = "OCC-B-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)
        val clusterId = createCluster("OCC-B-CLU-$s")
        val storageAreaId = createStorageArea("OCC-B-SA-$s", listOf(clusterId))
        val ltId = createLocationType("OCC-B-LT-$s")
        val layoutAreaId = createLayoutArea("OCC-B-LAREA-$s")
        val reservedLoc = createLocation("OCC-B-LOC-RESV-$s", ltId, layoutAreaId, clusterId)
        val freeLoc = createLocation("OCC-B-LOC-FREE-$s", ltId, layoutAreaId, clusterId)
        check(reservedLoc < freeLoc) { "fixture assumption: reservedLoc must sort before freeLoc" }
        createReservation(reservedLoc, percent = BigDecimal("100"), expiresAt = Instant.now().plus(Duration.ofMinutes(10)))
        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, BigDecimal("50"), null)
        seedOutsideSource(s, itemDataId, itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9311L)

        val task = result.generated.single { it.itemDataAreaId == itemDataAreaId }
        val order = taskService.findById(task.taskId, 9311L)
        assertThat(order.destinationLocationId).isEqualTo(freeLoc)
        assertThat(order.destinationLocationId).isNotEqualTo(reservedLoc)
    }

    // ── (i) Row 2 (defect-burndown-4, Task 5): cross-mode destination exclusion ──────────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9312"), Claim(key = "tenant_code", value = "ACME")])
    fun `a fix face inside a deficient area's own cluster never receives a duplicate Mode-2 order`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-XM-$s")
        val itemNumber = "XM-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)
        val clusterId = createCluster("XM-CLU-$s")
        val storageAreaId = createStorageArea("XM-SA-$s", listOf(clusterId))
        val ltId = createLocationType("XM-LT-$s")
        val layoutAreaId = createLayoutArea("XM-LAREA-$s")
        // The ONLY location in this cluster IS the fix face -- before the fix, Mode-2's
        // lowest-id pick has no other candidate to fall back to.
        val fixFaceLocationId = createLocation("XM-LOC-FACE-$s", ltId, layoutAreaId, clusterId)

        val fixAssignmentId = createFixAssignment(fixFaceLocationId, itemDataId, minAmount = 10)
        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, BigDecimal("50"), null)

        // Two outside sources so a would-be Mode-2 pick never fails on source exhaustion alone --
        // isolates the destination-exclusion defect (row 2) from the source-claim defect (row 3).
        seedOutsideSource(s, itemDataId, itemNumber, BigDecimal("100"))
        seedOutsideSource("$s-2", itemDataId, itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9312L)

        assertThat(result.generated.map { it.itemDataAreaId }).doesNotContain(itemDataAreaId)
        val itemTasks = result.generated.filter { it.itemDataNumber == itemNumber }
        assertThat(itemTasks).hasSize(1)
        assertThat(itemTasks.single().fixAssignmentId).isEqualTo(fixAssignmentId)
    }

    // ── (j) Row 3 (defect-burndown-4, Task 5): source-UL claim exclusion -- two fix faces ─────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9313"), Claim(key = "tenant_code", value = "ACME")])
    fun `two deficient fix faces for the same item claim different source unit-loads in one scan pass`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-SRC-A-$s")
        val itemNumber = "SRC-A-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)
        val ltId = createLocationType("SRC-A-LT-$s")
        val layoutAreaId = createLayoutArea("SRC-A-LAREA-$s")
        val faceLocA = createLocation("SRC-A-LOC-A-$s", ltId, layoutAreaId)
        val faceLocB = createLocation("SRC-A-LOC-B-$s", ltId, layoutAreaId)

        val fixA = createFixAssignment(faceLocA, itemDataId, minAmount = 10)
        val fixB = createFixAssignment(faceLocB, itemDataId, minAmount = 10)

        val source1 = seedOutsideSource("$s-1", itemDataId, itemNumber, BigDecimal("50"))
        val source2 = seedOutsideSource("$s-2", itemDataId, itemNumber, BigDecimal("60"))

        val result = replenishmentService.scan(9313L)

        val tasks = result.generated.filter { it.fixAssignmentId == fixA || it.fixAssignmentId == fixB }
        assertThat(tasks).hasSize(2)
        // Without Row 3's fix, both faces independently pick the same FIFO-first source --
        // this asserts the two minted orders name DIFFERENT source unit-loads.
        assertThat(tasks.map { it.unitLoadId }).containsExactlyInAnyOrder(source1, source2)
    }

    // ── (k) Row 3: source-UL claim exclusion -- a fix face and its item's area compete ─────────

    @Test
    @TestSecurity(user = "mgr", roles = ["layout-read", "layout-write", "product-read", "product-write", "inventory-read", "inventory-write"])
    @OidcSecurity(claims = [Claim(key = "client_id", value = "9314"), Claim(key = "tenant_code", value = "ACME")])
    fun `a fix face and its item's deficient area only let one of them claim a single eligible source`() {
        val s = ns()
        val itemUnitId = createItemUnit("IU-SRC-B-$s")
        val itemNumber = "SRC-B-SKU-$s"
        val itemDataId = createProduct(itemNumber, itemUnitId)

        val ltId = createLocationType("SRC-B-LT-$s")
        val layoutAreaId = createLayoutArea("SRC-B-LAREA-$s")
        val faceLocationId = createLocation("SRC-B-LOC-FACE-$s", ltId, layoutAreaId)
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 10)

        val clusterId = createCluster("SRC-B-CLU-$s")
        val storageAreaId = createStorageArea("SRC-B-SA-$s", listOf(clusterId))
        val areaLocationId = createLocation("SRC-B-LOC-AREA-$s", ltId, layoutAreaId, clusterId)
        val itemDataAreaId = createItemDataArea(itemDataId, storageAreaId, BigDecimal("50"), null)

        // ONE eligible source only -- both deficiencies compete for it.
        val sourceUlId = seedOutsideSource(s, itemDataId, itemNumber, BigDecimal("100"))

        val result = replenishmentService.scan(9314L)

        // Mode 1 (fix-face) runs first and claims the only source. Without Row 3's fix, Mode 2
        // (area) would independently select the SAME already-claimed unit-load.
        val claimants = result.generated.filter { it.unitLoadId == sourceUlId }
        assertThat(claimants).hasSize(1)
        assertThat(claimants.single().fixAssignmentId).isEqualTo(fixAssignmentId)
        assertThat(result.generated.map { it.itemDataAreaId }).doesNotContain(itemDataAreaId)
        assertThat(result.shortfalls.single { it.itemDataAreaId == itemDataAreaId }.reason).isEqualTo("NO_SOURCE")
    }
}

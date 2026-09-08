package com.karyo.replenishment

import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.orders.vo.OrderState
import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import com.karyo.tasks.dto.CompleteTransportOrderRequest
import com.karyo.tasks.service.TaskService
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * R13 (replenishment sprint, Task 2) — Karyo-original fill-to-max top-up replenishment,
 * exercised end-to-end through the real [ReplenishmentService.scan] and [TaskService.complete]
 * entry points (no mocks): a fix face below `maxAmount` mints a REPLENISH order carrying a
 * computed deficit quantity, and completing it moves exactly that much rather than the whole
 * source unit-load.
 *
 * NEW BEHAVIOR, not legacy parity — the myWMS behavioral corpus never computed a quantity or
 * read `FixAssignment.maxAmount` for replenishment; it always relocated the whole reserve
 * unit-load. `maxAmount == null` (or a deficit `>=` the source's own amount) still takes that
 * original whole-UL path unchanged (case (c)/(b) below).
 *
 * Fixture idiom mirrors [com.karyo.layout.FixAssignmentLookupTest] (item-unit → product →
 * location-type → area → location → fix-assignment) and
 * [com.karyo.inventory.ReplenishmentSourceSelectorTest] (REST-seeded reserve unit-loads) for the
 * REST-facing pieces, and [com.karyo.tasks.PartialMoveFlowTest] (direct-repository UL/stock
 * seeding) for the face pick-bin, so `UnitLoadType.aggregateStocks` and `UnitLoad.state` can be
 * controlled precisely. A dedicated client id (7401) isolates this suite from every other
 * client-1 fix assignment in the shared test DB — [ReplenishmentService.scan] processes EVERY
 * fix assignment visible to the client id it's given, so scanning under the shared "1" tenant
 * would pick up unrelated fixtures from other test classes.
 */
@QuarkusTest
class ReplenishmentTopUpFlowTest {

    @Inject
    lateinit var replenishmentService: ReplenishmentService

    @Inject
    lateinit var taskService: TaskService

    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    /**
     * Direct-CDI mutations (`taskService.complete(...)`) and the direct-repository reads that
     * follow share the same request-scoped persistence context. Without a `.clear()` in between,
     * a repository entity already resident in that context's L1 cache from an EARLIER seed call
     * (e.g. [seedFaceStock]'s own persist) wins over the just-committed row, so a post-complete
     * read can see pre-mutation values even though the DB itself is correct — an established gotcha
     * in this codebase (see `PickCancelServiceTest`/`OrderCancelReconciliationTest` for the same
     * `entityManager.clear()` idiom after a direct-CDI mutation, before asserting via repository).
     */
    @Inject
    lateinit var entityManager: EntityManager

    companion object {
        private const val CLIENT_ID = 7401L
    }

    // ── REST seed helpers (mirrors FixAssignmentLookupTest) ────────────────

    private fun createItemUnit(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","unitType":"PIECE"}""")
            .`when`().post("/api/v1/item-units").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createProduct(number: String, itemUnitId: Long): Long =
        given().contentType(ContentType.JSON)
            .body("""{"number":"$number","name":"RT Product","itemUnitId":$itemUnitId}""")
            .`when`().post("/api/v1/products").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-types").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createArea(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/areas").then().statusCode(201)
            .extract().jsonPath().getLong("id")

    private fun createLocation(name: String, locationTypeId: Long, areaId: Long): JsonPath =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$locationTypeId,"areaId":$areaId}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath()

    private fun createFixAssignment(locationId: Long, itemDataId: Long, minAmount: Int, maxAmount: Int?): Long {
        val maxPart = maxAmount?.let { ""","maxAmount":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"locationId":$locationId,"itemDataId":$itemDataId,"minAmount":$minAmount$maxPart}""")
            .`when`().post("/api/v1/fix-assignments").then().statusCode(201)
            .extract().jsonPath().getLong("id")
    }

    private fun createSourceUnitLoad(label: String, locationId: Long): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":$locationId,"storageLocationName":"LOC-$locationId"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createSourceStock(ulId: Long, itemDataId: Long, itemNumber: String, amount: BigDecimal): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"itemDataId":$itemDataId,"itemDataNumber":"$itemNumber",""" +
                    """"amount":$amount,"unitLoadId":$ulId,"state":300}""",
            )
            .`when`().post("/api/v1/stock-units")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun getAmount(itemDataId: Long, locationId: Long): BigDecimal =
        given()
            .`when`().get("/api/v1/stock-units/amount?itemDataId=$itemDataId&locationId=$locationId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("amount").let { BigDecimal.valueOf(it) }

    // ── Direct-repository face fixture (mirrors PartialMoveFlowTest — fine control over
    //    UnitLoadType.aggregateStocks and UnitLoad.state, neither of which the REST unit-load
    //    endpoint lets a caller set) ───────────────────────────────────────

    @Transactional
    fun seedFaceUnitLoad(owner: Long, type: UnitLoadType, locationId: Long, locationName: String): Long {
        val ul = UnitLoad().apply {
            clientId = owner
            labelId = "RT-FACE-UL-${System.nanoTime()}"
            unitLoadType = type
            storageLocationId = locationId
            storageLocationName = locationName
            // DefaultStockMover.resolveTargetUnitLoad only reuses ON_STOCK, unlocked candidates
            // (the same physically-settled window StockUnitLookup.occupancyByLocationIds uses) —
            // REST-created unit loads default to UNDEFINED, which this fixture must override for
            // the merge-onto-existing-face-stock assertion in case (a) to be meaningful.
            state = StockState.ON_STOCK.code
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    @Transactional
    fun seedFaceStock(unitLoadId: Long, owner: Long, itemDataId: Long, itemNumber: String, amount: BigDecimal): Long {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        val su = StockUnit().apply {
            clientId = owner
            this.itemDataId = itemDataId
            itemDataNumber = itemNumber
            this.amount = amount
            state = StockState.ON_STOCK.code
            unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return su.id!!
    }

    /**
     * Task 3 (defect-burndown-4, row 31) fixture: changes an already-seeded source stock unit's
     * amount after the REPLENISH order minted from it -- reproduces a concurrent pick/transfer
     * (or another top-up merge) landing on the same reserve unit load between mint and complete,
     * the drift `ConfirmVariantService.liveSingleStockAmount`'s live re-read is meant to survive.
     */
    @Transactional
    fun changeSourceStockAmount(stockUnitId: Long, newAmount: BigDecimal) {
        val stock = stockUnitRepository.findById(stockUnitId)!!
        stock.amount = newAmount
    }

    private fun primeTenant() {
        tenantContext.clientId = CLIENT_ID
        tenantContext.principalKind = PrincipalKind.OWNER
    }

    // ── (a) genuine top-up: deficit < source amount -> partial merges onto face ────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "7401"), Claim(key = "tenant_code", value = "ACME")])
    fun `R13(a) a deficit smaller than the source moves exactly the top-up amount and merges onto the existing face stock`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RT-A-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RT-A-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RT-A-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RT-A-${ns.toString().takeLast(8)}")
        val faceLoc = createLocation("LOC-RT-A-${ns.toString().takeLast(8)}", ltId, areaId)
        val faceLocationId = faceLoc.getLong("id")
        val faceLocationName = faceLoc.getString("name")

        // maxAmount=100, currentAmount=40 (seeded below) -> deficit=60; source has 200 -> a
        // genuine partial (60 < 200).
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 50, maxAmount = 100)

        val pickBinType = unitLoadTypeRepository.findByName("Pick Bin")!!
        check(pickBinType.aggregateStocks) { "fixture assumption: Pick Bin must aggregate stocks" }
        val faceUlId = seedFaceUnitLoad(CLIENT_ID, pickBinType, faceLocationId, faceLocationName)
        val faceStockId = seedFaceStock(faceUlId, CLIENT_ID, itemDataId, "RT-A-SKU-$ns", BigDecimal("40"))

        val sourceLocationId = 7_401_100L
        val sourceUlId = createSourceUnitLoad("RT-A-SOURCE-$ns", sourceLocationId)
        val sourceStockId = createSourceStock(sourceUlId, itemDataId, "RT-A-SKU-$ns", BigDecimal("200"))

        primeTenant()
        val scanResult = replenishmentService.scan(CLIENT_ID)
        val generated = scanResult.generated.first { it.fixAssignmentId == fixAssignmentId }

        val minted = taskService.findById(generated.taskId, CLIENT_ID)
        assertThat(minted.amount).isEqualByComparingTo(BigDecimal("60")) // R13 top-up quantity, not the full 200

        taskService.assign(minted.id, "op-rt-a", CLIENT_ID)
        taskService.start(minted.id, CLIENT_ID)
        // No amount in the request -- R13's default-from-order.amount rule must supply the 60.
        val completed = taskService.complete(minted.id, CompleteTransportOrderRequest(), CLIENT_ID)

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("60"))
        assertThat(completed.destinationLocationId).isEqualTo(faceLocationId)
        entityManager.clear()

        // Merged onto the EXISTING face stock row (aggregateStocks=true), not a new one.
        val faceStock = stockUnitRepository.findById(faceStockId)!!
        assertThat(faceStock.amount).isEqualByComparingTo(BigDecimal("100")) // 40 + 60
        assertThat(stockUnitRepository.findByUnitLoadId(faceUlId)).hasSize(1)

        val faceTotal = getAmount(itemDataId, faceLocationId)
        assertThat(faceTotal).isEqualByComparingTo(BigDecimal("100"))

        // Remainder stays at the source -- a genuine partial, not a drain.
        val sourceRemainder = stockUnitRepository.findById(sourceStockId)!!
        assertThat(sourceRemainder.amount).isEqualByComparingTo(BigDecimal("140")) // 200 - 60
        assertThat(sourceRemainder.state).isEqualTo(StockState.ON_STOCK.code)

        // (d) the layout reservation for this order is fully released post-completion. REPLENISH
        // orders never call LocationFinder.findPutawayLocation (the destination is the fix face,
        // known at creation) so none was ever held -- this assertion documents that invariant the
        // same way PartialMoveFlowTest's release checks do for the putaway path.
        assertThat(reservationRepository.findByTransportOrderId(minted.id)).isEmpty()
    }

    // ── (a2) coordinator-review fix (IMPORTANT-1): merge gesture also honors the top-up ────

    /**
     * IMPORTANT-1 regression pin: completing a top-up order via
     * [CompleteTransportOrderRequest.destinationUnitLoadId] (the natural gesture when an
     * operator scans the destination face unit load directly, amount omitted) must ALSO receive
     * the R13-defaulted top-up amount -- not merge the WHOLE source stock past the face's
     * `maxAmount`. Before the fix, [TaskService.complete] computed the default AFTER the merge
     * dispatch, so this gesture skipped the default entirely and fell through to
     * [ConfirmVariantService.completeAsMerge]'s `amount == null` branch, which merges the FULL
     * source stock (200, not the top-up's 60).
     */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "7401"), Claim(key = "tenant_code", value = "ACME")])
    fun `R13(a2) merge gesture -- destinationUnitLoadId only, amount omitted, still honors the defaulted top-up amount`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RT-A2-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RT-A2-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RT-A2-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RT-A2-${ns.toString().takeLast(8)}")
        val faceLoc = createLocation("LOC-RT-A2-${ns.toString().takeLast(8)}", ltId, areaId)
        val faceLocationId = faceLoc.getLong("id")
        val faceLocationName = faceLoc.getString("name")

        // Same shape as (a): maxAmount=100, currentAmount=40 -> deficit=60; source has 200.
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 50, maxAmount = 100)

        val pickBinType = unitLoadTypeRepository.findByName("Pick Bin")!!
        val faceUlId = seedFaceUnitLoad(CLIENT_ID, pickBinType, faceLocationId, faceLocationName)
        val faceStockId = seedFaceStock(faceUlId, CLIENT_ID, itemDataId, "RT-A2-SKU-$ns", BigDecimal("40"))

        val sourceLocationId = 7_401_150L
        val sourceUlId = createSourceUnitLoad("RT-A2-SOURCE-$ns", sourceLocationId)
        val sourceStockId = createSourceStock(sourceUlId, itemDataId, "RT-A2-SKU-$ns", BigDecimal("200"))

        primeTenant()
        val scanResult = replenishmentService.scan(CLIENT_ID)
        val generated = scanResult.generated.first { it.fixAssignmentId == fixAssignmentId }

        val minted = taskService.findById(generated.taskId, CLIENT_ID)
        assertThat(minted.amount).isEqualByComparingTo(BigDecimal("60"))

        taskService.assign(minted.id, "op-rt-a2", CLIENT_ID)
        taskService.start(minted.id, CLIENT_ID)
        // Merge gesture: operator scans the destination face UL directly, no amount specified,
        // no destinationLocationId (the 400 guard forces the two to be mutually exclusive).
        val completed = taskService.complete(
            minted.id,
            CompleteTransportOrderRequest(destinationUnitLoadId = faceUlId),
            CLIENT_ID,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("60")) // NOT 200
        entityManager.clear()

        val faceStock = stockUnitRepository.findById(faceStockId)!!
        assertThat(faceStock.amount).isEqualByComparingTo(BigDecimal("100")) // 40 + 60, face stays under maxAmount
        assertThat(stockUnitRepository.findByUnitLoadId(faceUlId)).hasSize(1)

        // Remainder stays at the source -- a genuine partial merge, not a full drain.
        val sourceRemainder = stockUnitRepository.findById(sourceStockId)!!
        assertThat(sourceRemainder.amount).isEqualByComparingTo(BigDecimal("140")) // 200 - 60
        assertThat(sourceRemainder.state).isEqualTo(StockState.ON_STOCK.code)
    }

    // ── (b) deficit >= source amount -> whole-UL move, not a partial ───────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "7401"), Claim(key = "tenant_code", value = "ACME")])
    fun `R13(b) a source smaller than the deficit is a whole-UL move, command amount is null`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RT-B-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RT-B-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RT-B-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RT-B-${ns.toString().takeLast(8)}")
        val faceLoc = createLocation("LOC-RT-B-${ns.toString().takeLast(8)}", ltId, areaId)
        val faceLocationId = faceLoc.getLong("id")

        // maxAmount=60, currentAmount=0 (no face stock seeded) -> deficit=60; source only has 30
        // -> requested = min(30,60) = 30 == source.amount -> null (whole-UL), same outcome either way.
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 10, maxAmount = 60)

        val sourceLocationId = 7_401_200L
        val sourceUlId = createSourceUnitLoad("RT-B-SOURCE-$ns", sourceLocationId)
        createSourceStock(sourceUlId, itemDataId, "RT-B-SKU-$ns", BigDecimal("30"))

        primeTenant()
        val scanResult = replenishmentService.scan(CLIENT_ID)
        val generated = scanResult.generated.first { it.fixAssignmentId == fixAssignmentId }

        val minted = taskService.findById(generated.taskId, CLIENT_ID)
        assertThat(minted.amount).isEqualByComparingTo(BigDecimal("30")) // denorm's whole-stock amount, no top-up cap

        taskService.assign(minted.id, "op-rt-b", CLIENT_ID)
        taskService.start(minted.id, CLIENT_ID)
        val completed = taskService.complete(minted.id, CompleteTransportOrderRequest(), CLIENT_ID)

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("30"))
        entityManager.clear()

        // Whole-UL move: the SOURCE unit load itself relocated to the face (unlike (a)'s partial,
        // which leaves the source unit load in place and only moves stock off it).
        val movedUl = unitLoadRepository.findById(sourceUlId)!!
        assertThat(movedUl.storageLocationId).isEqualTo(faceLocationId)
        assertThat(getAmount(itemDataId, faceLocationId)).isEqualByComparingTo(BigDecimal("30"))
    }

    // ── (c) no maxAmount configured -> whole-UL, parity unchanged ──────────

    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "7401"), Claim(key = "tenant_code", value = "ACME")])
    fun `R13(c) no maxAmount configured leaves the parity whole-UL path unchanged`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RT-C-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RT-C-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RT-C-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RT-C-${ns.toString().takeLast(8)}")
        val faceLoc = createLocation("LOC-RT-C-${ns.toString().takeLast(8)}", ltId, areaId)
        val faceLocationId = faceLoc.getLong("id")

        // No maxAmount -> topUpAmount short-circuits to null regardless of the source's size.
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 10, maxAmount = null)

        val sourceLocationId = 7_401_300L
        val sourceUlId = createSourceUnitLoad("RT-C-SOURCE-$ns", sourceLocationId)
        createSourceStock(sourceUlId, itemDataId, "RT-C-SKU-$ns", BigDecimal("25"))

        primeTenant()
        val scanResult = replenishmentService.scan(CLIENT_ID)
        val generated = scanResult.generated.first { it.fixAssignmentId == fixAssignmentId }

        val minted = taskService.findById(generated.taskId, CLIENT_ID)
        assertThat(minted.amount).isEqualByComparingTo(BigDecimal("25")) // denorm only, no top-up computation ran

        taskService.assign(minted.id, "op-rt-c", CLIENT_ID)
        taskService.start(minted.id, CLIENT_ID)
        val completed = taskService.complete(minted.id, CompleteTransportOrderRequest(), CLIENT_ID)

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("25"))
        entityManager.clear()
        val movedUl = unitLoadRepository.findById(sourceUlId)!!
        assertThat(movedUl.storageLocationId).isEqualTo(faceLocationId)
    }

    // ── (e) row 6: staging-drop completion of a top-up order must report the ACTUAL moved
    //     amount, not the R13 top-up snapshot ────────────────────────────────

    /**
     * Task 3 (defect-burndown-4, row 6) regression pin: an explicit `destinationLocationId` (the
     * "drop at a specific spot" gesture, e.g. a transfer-staging location) bypasses the R13
     * default-amount rule entirely (complete()'s KDoc, gesture (c)) and falls through to the
     * whole-UL move -- which physically relocates the ENTIRE source unit load, not the R13
     * top-up snapshot stamped on [TransportOrder.amount]. Before the fix,
     * `order.confirmedAmount = order.amount` recorded the top-up's 10 even though the whole
     * 40-unit source stock moved.
     */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "7401"), Claim(key = "tenant_code", value = "ACME")])
    fun `Task3 row6 -- staging-drop completion of a top-up order records the whole ULs actual amount, not the order-amount snapshot`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RT-E-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RT-E-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RT-E-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RT-E-${ns.toString().takeLast(8)}")
        val faceLoc = createLocation("LOC-RT-E-${ns.toString().takeLast(8)}", ltId, areaId)
        val faceLocationId = faceLoc.getLong("id")

        // maxAmount=10, currentAmount=0 (no face stock seeded) -> deficit=10; source has 40 ->
        // genuine top-up (10 < 40) -> order.amount (the R13 snapshot) is 10, NOT the source's 40.
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 5, maxAmount = 10)

        val sourceLocationId = 7_401_400L
        val sourceUlId = createSourceUnitLoad("RT-E-SOURCE-$ns", sourceLocationId)
        createSourceStock(sourceUlId, itemDataId, "RT-E-SKU-$ns", BigDecimal("40"))

        primeTenant()
        val scanResult = replenishmentService.scan(CLIENT_ID)
        val generated = scanResult.generated.first { it.fixAssignmentId == fixAssignmentId }

        val minted = taskService.findById(generated.taskId, CLIENT_ID)
        assertThat(minted.amount).isEqualByComparingTo(BigDecimal("10")) // R13 top-up snapshot, not the source's 40

        taskService.assign(minted.id, "op-rt-e", CLIENT_ID)
        taskService.start(minted.id, CLIENT_ID)

        // Staging-drop gesture: explicit destinationLocationId bypasses the R13 default-amount
        // rule and falls through to the whole-UL move, physically relocating the ENTIRE source
        // stock (40), not the order's 10 snapshot.
        val stagingLoc = createLocation("LOC-RT-E-STAGE-${ns.toString().takeLast(8)}", ltId, areaId)
        val stagingLocationId = stagingLoc.getLong("id")
        val stagingLocationName = stagingLoc.getString("name")
        val completed = taskService.complete(
            minted.id,
            CompleteTransportOrderRequest(destinationLocationId = stagingLocationId, destinationLocationName = stagingLocationName),
            CLIENT_ID,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        // Task 3 row 6: before the fix this asserted 10 (order.amount) even though the whole UL
        // (40) physically moved.
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("40"))
        entityManager.clear()

        val movedUl = unitLoadRepository.findById(sourceUlId)!!
        assertThat(movedUl.storageLocationId).isEqualTo(stagingLocationId)
        assertThat(getAmount(itemDataId, stagingLocationId)).isEqualByComparingTo(BigDecimal("40"))
    }

    // ── (f) row 31: source shrinkage between mint and complete must report the LIVE amount
    //     actually moved, not the stale order.amount snapshot ─────────────────

    /**
     * Task 3 (defect-burndown-4, row 31) regression pin, shrink direction: the source stock unit
     * shrinks after this order was minted (e.g. a concurrent partial pick/transfer against the
     * same stock unit). [ConfirmVariantService.liveSingleStockAmount] re-reads the LIVE amount
     * for [TransportOrder.confirmedAmount] on the whole-UL completion path -- before the fix, the
     * whole-UL move would relocate the shrunk 25 but record `confirmedAmount = 40` (the stale
     * mint-time snapshot).
     *
     * Deliberately SHRINK, not growth: shrinking [order.amount] staying ABOVE the live amount
     * (`40 >= 25`) still correctly resolves to the whole-UL branch even with the stale value --
     * only the REPORTED figure needs the live re-read, which is what this pin exercises. Growth
     * (a stale snapshot BELOW the live amount) reaching this same default-gesture path is a
     * documented residual gap -- see `TaskService.complete`'s "R13 default-amount rule" KDoc for
     * why that branch decision deliberately keeps using the snapshot (it cannot safely
     * distinguish a genuine R13 top-up cap from a stale whole-UL denorm using [order.amount]
     * alone, and switching it to a live re-read reintroduces the bug IMPORTANT-1's regression pin
     * (`R13(a2)` above) exists to prevent).
     */
    @Test
    @TestSecurity(
        user = "mgr",
        roles = ["layout-read", "layout-write", "inventory-read", "inventory-write", "product-read", "product-write"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "7401"), Claim(key = "tenant_code", value = "ACME")])
    fun `Task3 row31 -- source shrinkage between mint and complete reports the live amount, not the stale order-amount snapshot`() {
        val ns = System.nanoTime()
        val itemUnitId = createItemUnit("IU-RT-F-${ns.toString().takeLast(8)}")
        val itemDataId = createProduct("RT-F-SKU-$ns", itemUnitId)
        val ltId = createLocationType("LT-RT-F-${ns.toString().takeLast(8)}")
        val areaId = createArea("AREA-RT-F-${ns.toString().takeLast(8)}")
        val faceLoc = createLocation("LOC-RT-F-${ns.toString().takeLast(8)}", ltId, areaId)
        val faceLocationId = faceLoc.getLong("id")

        // No maxAmount -> topUpAmount short-circuits to null -> order.amount is the plain
        // whole-stock denorm (40) at mint time, same shape as case (c).
        val fixAssignmentId = createFixAssignment(faceLocationId, itemDataId, minAmount = 10, maxAmount = null)

        val sourceLocationId = 7_401_450L
        val sourceUlId = createSourceUnitLoad("RT-F-SOURCE-$ns", sourceLocationId)
        val sourceStockId = createSourceStock(sourceUlId, itemDataId, "RT-F-SKU-$ns", BigDecimal("40"))

        primeTenant()
        val scanResult = replenishmentService.scan(CLIENT_ID)
        val generated = scanResult.generated.first { it.fixAssignmentId == fixAssignmentId }

        val minted = taskService.findById(generated.taskId, CLIENT_ID)
        assertThat(minted.amount).isEqualByComparingTo(BigDecimal("40")) // mint-time snapshot

        taskService.assign(minted.id, "op-rt-f", CLIENT_ID)
        taskService.start(minted.id, CLIENT_ID)

        // The source stock SHRINKS between mint and complete -- order.amount stays a stale 40.
        changeSourceStockAmount(sourceStockId, BigDecimal("25"))
        entityManager.clear()

        // No amount, no destinationLocationId -- the R13 default-amount rule defaults to the
        // stale 40 (unchanged; `40 >= 25` still correctly resolves to the whole-UL branch), but
        // the whole-UL completion step must report the LIVE amount (25) actually moved.
        val completed = taskService.complete(minted.id, CompleteTransportOrderRequest(), CLIENT_ID)

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        // Task 3 row 31: before the fix this asserted 40 (the stale mint-time snapshot).
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("25"))
        entityManager.clear()

        val movedUl = unitLoadRepository.findById(sourceUlId)!!
        assertThat(movedUl.storageLocationId).isEqualTo(faceLocationId)
        assertThat(getAmount(itemDataId, faceLocationId)).isEqualByComparingTo(BigDecimal("25"))
    }
}

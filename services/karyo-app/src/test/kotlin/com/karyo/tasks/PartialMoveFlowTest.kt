package com.karyo.tasks

import com.karyo.events.outbox.OutboxEventRepository
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.orders.vo.OrderState
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.dto.CompleteTransportOrderRequest
import com.karyo.tasks.dto.CreateTransportOrderRequest
import com.karyo.tasks.exception.TaskException
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.service.TaskService
import com.karyo.tasks.vo.TransportType
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * PT17 (putaway-transport sprint, Task 4) — ERP references + partial-quantity transport
 * confirms, exercised through the real [TaskService.complete]/[TaskService.createManualMove]
 * entry points. The partial branches live on [com.karyo.tasks.service.ConfirmVariantService];
 * this suite pins its externally-visible behavior, not its internals.
 *
 * Fixture idiom mirrors [ConfirmMergeFlowTest] (direct-repository UL/stock seeding for fine
 * control over `UnitLoadType.aggregateStocks` and multi-stock loads) plus
 * [TransferChainFlowTest]'s REST layout helpers where a REAL location is needed (find-or-create
 * routing, transfer-staging no-successor pin).
 */
@QuarkusTest
class PartialMoveFlowTest {

    @Inject
    lateinit var taskService: TaskService

    @Inject
    lateinit var repository: TransportOrderRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var unitLoadTypeRepository: UnitLoadTypeRepository

    @Inject
    lateinit var outboxRepository: OutboxEventRepository

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Direct-repository fixture helpers (mirrors ConfirmMergeFlowTest) ───────

    @Transactional
    fun seedUnitLoad(owner: Long, type: UnitLoadType, locationId: Long, locationName: String): Long {
        val ul = UnitLoad().apply {
            clientId = owner
            labelId = "PARTIAL-UL-${System.nanoTime()}"
            unitLoadType = type
            storageLocationId = locationId
            storageLocationName = locationName
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    @Transactional
    fun seedStock(
        unitLoadId: Long,
        owner: Long,
        itemDataId: Long,
        itemNumber: String,
        amount: BigDecimal,
        reservedAmount: BigDecimal = BigDecimal.ZERO,
    ): Long {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        val su = StockUnit().apply {
            clientId = owner
            this.itemDataId = itemDataId
            itemDataNumber = itemNumber
            this.amount = amount
            this.reservedAmount = reservedAmount
            state = StockState.ON_STOCK.code
            unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return su.id!!
    }

    @Transactional
    fun seedOrder(
        clientId: Long,
        unitLoadId: Long,
        state: Int,
        suggestedLocationId: Long? = null,
        suggestedLocationName: String? = null,
    ): Long {
        val seq = System.nanoTime()
        val order = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "TO-PARTIAL-TEST-$seq"
            transportType = TransportType.MOVE
            this.unitLoadId = unitLoadId
            unitLoadLabel = "UL-PARTIAL-$seq"
            sourceLocationId = 1
            sourceLocationName = "DOCK-1"
            this.suggestedLocationId = suggestedLocationId
            this.suggestedLocationName = suggestedLocationName
            this.state = state
            executorType = "HUMAN"
        }
        repository.persist(order)
        return order.id!!
    }

    private fun completedPayload(taskId: Long): String =
        outboxRepository.find(
            "aggregateType = ?1 and aggregateId = ?2 and eventType = ?3",
            "TransportOrder", taskId, "TransportOrderCompleted",
        ).firstResult()!!.payload

    // ── Layout fixture helpers (mirrors TransferChainFlowTest/ConfirmMergeFlowTest) ──

    private fun createArea(name: String, usages: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","usages":["$usages"]}""")
            .`when`().post("/api/v1/areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocationType(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name","liftingCapacity":1000}""")
            .`when`().post("/api/v1/location-types")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createLocation(name: String, typeId: Long, areaId: Long, clusterId: Long? = null): JsonPath {
        val clusterPart = clusterId?.let { ""","locationClusterId":$it""" } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","locationTypeId":$typeId,"areaId":$areaId$clusterPart}""")
            .`when`().post("/api/v1/locations")
            .then().statusCode(201).extract().jsonPath()
    }

    private fun createLocationCluster(name: String): Long =
        given().contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`().post("/api/v1/location-clusters")
            .then().statusCode(201).extract().jsonPath().getLong("id")

    private fun createStorageArea(name: String, clusterIds: List<Long>, transferStaging: Boolean): Long {
        val ids = clusterIds.joinToString(",")
        return given().contentType(ContentType.JSON)
            .body("""{"name":"$name","clusterIds":[$ids],"transferStaging":$transferStaging}""")
            .`when`().post("/api/v1/storage-areas")
            .then().statusCode(201).extract().jsonPath().getLong("id")
    }

    /** Mirrors [com.karyo.app.UnitLoadTransferCdiFlowTest]'s idiom for reading back allocation. */
    private fun getAllocation(locationId: Long): Double =
        given()
            .`when`().get("/api/v1/locations/$locationId")
            .then().statusCode(200)
            .extract().jsonPath().getDouble("allocation")

    /**
     * `seedUnitLoad` leaves `UnitLoad.state` at its entity default (UNDEFINED, 0) -- fine for
     * every OTHER fixture in this class since none of them exercise
     * `DefaultStockMover.resolveTargetUnitLoad`'s reuse-candidate scan (the merge tests skip the
     * scan entirely; the plain create-new tests rely on there being NO candidates at all). Test
     * (j) needs a candidate that WOULD be reused but for its `lockType`, so it must be lifted to
     * ON_STOCK first -- otherwise the state filter alone would exclude it and the test would pin
     * nothing about `lockType`.
     */
    @Transactional
    fun lockUnitLoad(unitLoadId: Long, lockType: Int = 1) {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        ul.state = StockState.ON_STOCK.code
        ul.lockType = lockType
    }

    // ── (a) partial to explicit unit load ───────────────────────────────────

    @Test
    fun `partial confirm to an explicit unit load leaves the remainder on source, folds onto target, stamps confirmedAmount + partial`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val aggregatingType = unitLoadTypeRepository.findByName("Pick Bin")!!
        check(aggregatingType.aggregateStocks) { "fixture assumption: Pick Bin must aggregate stocks" }
        val targetUlId = seedUnitLoad(1L, aggregatingType, 900_101L, "PARTIAL-TARGET-A-$s")
        val existingTargetStockId = seedStock(targetUlId, 1L, itemDataId = 9101L, itemNumber = "PARTIAL-SKU-A-$s", amount = BigDecimal("15"))

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 200L, "PARTIAL-SOURCE-A-$s")
        val sourceStockId = seedStock(sourceUlId, 1L, itemDataId = 9101L, itemNumber = "PARTIAL-SKU-A-$s", amount = BigDecimal("40"))

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId, amount = BigDecimal("25")),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("25"))
        assertThat(completed.destinationLocationId).isEqualTo(900_101L)

        val remainderSource = stockUnitRepository.findById(sourceStockId)!!
        assertThat(remainderSource.amount).isEqualByComparingTo(BigDecimal("15"))
        assertThat(remainderSource.state).isEqualTo(StockState.ON_STOCK.code) // NOT drained -- a genuine partial

        val foldedTarget = stockUnitRepository.findById(existingTargetStockId)!!
        assertThat(foldedTarget.amount).isEqualByComparingTo(BigDecimal("40")) // 15 + 25

        assertThat(completedPayload(orderId)).contains("\"partial\": true")
    }

    // ── (b) partial to a location with no reusable unit load ───────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `partial confirm to a location with no reusable unit load creates a new one of the source's own type`() {
        val s = System.nanoTime()
        val area = createArea("PT-AREA-$s", "STORAGE")
        val type = createLocationType("PT-LT-$s")
        val emptyLocation = createLocation("PT-EMPTY-LOC-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 201L, "PARTIAL-SOURCE-B-$s")
        val sourceStockId = seedStock(sourceUlId, 1L, itemDataId = 9102L, itemNumber = "PARTIAL-SKU-B-$s", amount = BigDecimal("40"))
        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(
                destinationLocationId = emptyLocation.getLong("id"),
                destinationLocationName = emptyLocation.getString("name"),
                amount = BigDecimal("10"),
            ),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.confirmedAmount).isEqualByComparingTo(BigDecimal("10"))
        assertThat(completed.destinationLocationId).isEqualTo(emptyLocation.getLong("id"))

        val newUls = unitLoadRepository.findByStorageLocationId(emptyLocation.getLong("id"))
        assertThat(newUls).hasSize(1)
        assertThat(newUls.first().unitLoadType.id).isEqualTo(plainType.id) // source's own type
        assertThat(newUls.first().id).isNotEqualTo(sourceUlId)

        val newStocks = stockUnitRepository.findByUnitLoadId(newUls.first().id!!)
        assertThat(newStocks).hasSize(1)
        assertThat(newStocks.first().amount).isEqualByComparingTo(BigDecimal("10"))

        val remainderSource = stockUnitRepository.findById(sourceStockId)!!
        assertThat(remainderSource.amount).isEqualByComparingTo(BigDecimal("30"))

        // Final-gate item 3 belt: DefaultStockMover's create-new branch used to bypass location
        // allocation entirely -- the freshly created unit load never fired UnitLoadTransferredEvent,
        // so the destination's allocation stayed at 0 despite a real pallet now sitting on it.
        assertThat(getAllocation(emptyLocation.getLong("id"))).isEqualTo(100.0)
    }

    // ── (c) partial exceeding available -> 409 InsufficientStock ───────────

    @Test
    fun `partial confirm exceeding the source's available amount surfaces 409 InsufficientStock`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val targetUlId = seedUnitLoad(1L, plainType, 900_102L, "PARTIAL-TARGET-C-$s")
        val sourceUlId = seedUnitLoad(1L, plainType, 202L, "PARTIAL-SOURCE-C-$s")
        // amount=40, reserved=35 -> availableAmount=5; requested amount=10 is < 40 (still a
        // genuine partial) but > the 5 actually available.
        seedStock(sourceUlId, 1L, itemDataId = 9103L, itemNumber = "PARTIAL-SKU-C-$s", amount = BigDecimal("40"), reservedAmount = BigDecimal("35"))

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        assertThatThrownBy {
            taskService.complete(
                orderId,
                CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId, amount = BigDecimal("10")),
                1L,
            )
        }.isInstanceOf(InventoryException.InsufficientStock::class.java)

        val order = repository.findByIdAndClient(orderId, 1L)!!
        assertThat(order.state).isEqualTo(OrderState.STARTED.code) // refusal precedes any mutation
    }

    // ── (d) multi-stock source + amount -> 409 MixedSourceLoad ─────────────

    @Test
    fun `partial confirm on a multi-stock source unit load is refused with 409 MixedSourceLoad`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 203L, "PARTIAL-SOURCE-D-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9104L, itemNumber = "PARTIAL-SKU-D1-$s", amount = BigDecimal("10"))
        seedStock(sourceUlId, 1L, itemDataId = 9105L, itemNumber = "PARTIAL-SKU-D2-$s", amount = BigDecimal("10"))

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code, suggestedLocationId = 900_103L, suggestedLocationName = "PARTIAL-TARGET-D")

        assertThatThrownBy {
            taskService.complete(orderId, CompleteTransportOrderRequest(amount = BigDecimal("5")), 1L)
        }.isInstanceOf(TaskException.MixedSourceLoad::class.java)

        val order = repository.findByIdAndClient(orderId, 1L)!!
        assertThat(order.state).isEqualTo(OrderState.STARTED.code)
    }

    // ── (e) full confirm still writes confirmedAmount (null on multi-stock) ─

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `an ordinary full whole-UL confirm stamps confirmedAmount from the denorm, and leaves it null when the source was multi-stock at creation`() {
        val s = System.nanoTime()
        val area = createArea("PT-E-AREA-$s", "STORAGE")
        val type = createLocationType("PT-E-LT-$s")
        val destSingle = createLocation("PT-E-DEST-SINGLE-$s", type, area)
        val destMulti = createLocation("PT-E-DEST-MULTI-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!

        // Single-stock source: denorm populates order.amount at creation.
        val singleUlId = seedUnitLoad(1L, plainType, 204L, "PARTIAL-SOURCE-E1-$s")
        seedStock(singleUlId, 1L, itemDataId = 9106L, itemNumber = "PARTIAL-SKU-E1-$s", amount = BigDecimal("18"))
        val singleCreated = taskService.createManualMove(
            CreateTransportOrderRequest(unitLoadId = singleUlId, destinationLocationId = destSingle.getLong("id")),
            1L,
        )
        assertThat(singleCreated.amount).isEqualByComparingTo(BigDecimal("18")) // denorm worked
        taskService.assign(singleCreated.id, "op-e1", 1L)
        taskService.start(singleCreated.id, 1L)
        val singleCompleted = taskService.complete(singleCreated.id, CompleteTransportOrderRequest(), 1L)
        assertThat(singleCompleted.confirmedAmount).isEqualByComparingTo(BigDecimal("18"))

        // Multi-stock source: denorm is skipped (honest gap), so confirmedAmount stays null too.
        val multiUlId = seedUnitLoad(1L, plainType, 205L, "PARTIAL-SOURCE-E2-$s")
        seedStock(multiUlId, 1L, itemDataId = 9107L, itemNumber = "PARTIAL-SKU-E2A-$s", amount = BigDecimal("5"))
        seedStock(multiUlId, 1L, itemDataId = 9108L, itemNumber = "PARTIAL-SKU-E2B-$s", amount = BigDecimal("5"))
        val multiCreated = taskService.createManualMove(
            CreateTransportOrderRequest(unitLoadId = multiUlId, destinationLocationId = destMulti.getLong("id")),
            1L,
        )
        assertThat(multiCreated.amount).isNull()
        taskService.assign(multiCreated.id, "op-e2", 1L)
        taskService.start(multiCreated.id, 1L)
        val multiCompleted = taskService.complete(multiCreated.id, CompleteTransportOrderRequest(), 1L)
        assertThat(multiCompleted.confirmedAmount).isNull()
    }

    // ── (f) ERP refs round-trip create -> response -> search by q ──────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `externalNumber and externalId round-trip through create, the response, and free-text search`() {
        val s = System.nanoTime()
        val area = createArea("PT-F-AREA-$s", "STORAGE")
        val type = createLocationType("PT-F-LT-$s")
        val dest = createLocation("PT-F-DEST-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val ulId = seedUnitLoad(1L, plainType, 206L, "PARTIAL-SOURCE-F-$s")

        val erpNumber = "ERP-PO-$s"
        val erpId = "ERP-EXT-$s"
        val created = taskService.createManualMove(
            CreateTransportOrderRequest(
                unitLoadId = ulId,
                destinationLocationId = dest.getLong("id"),
                externalNumber = erpNumber,
                externalId = erpId,
            ),
            1L,
        )
        assertThat(created.externalNumber).isEqualTo(erpNumber)
        assertThat(created.externalId).isEqualTo(erpId)

        val fetched = taskService.findById(created.id, 1L)
        assertThat(fetched.externalNumber).isEqualTo(erpNumber)
        assertThat(fetched.externalId).isEqualTo(erpId)

        val page = taskService.list(1L, com.karyo.common.pagination.PaginationParams(), null, null, null, erpNumber)
        assertThat(page.content.map { it.id }).contains(created.id)
    }

    // ── (g) partial confirm at a staging-area location -> NO successor ─────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `partial confirm at a transfer-staging location mints no chain successor`() {
        val s = System.nanoTime()
        val area = createArea("PT-G-AREA-$s", "STORAGE")
        val type = createLocationType("PT-G-LT-$s")
        val stagingCluster = createLocationCluster("PT-G-CLUSTER-$s")
        createStorageArea("PT-G-STAGING-$s", listOf(stagingCluster), transferStaging = true)
        val stagingLoc = createLocation("PT-G-STAGING-LOC-$s", type, area, clusterId = stagingCluster)
        val otherFinalTarget = createLocation("PT-G-OTHER-FINAL-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 207L, "PARTIAL-SOURCE-G-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9109L, itemNumber = "PARTIAL-SKU-G-$s", amount = BigDecimal("40"))

        val orderId = seedOrder(
            1L, sourceUlId, OrderState.STARTED.code,
            suggestedLocationId = otherFinalTarget.getLong("id"), suggestedLocationName = otherFinalTarget.getString("name"),
        )

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(
                destinationLocationId = stagingLoc.getLong("id"),
                destinationLocationName = stagingLoc.getString("name"),
                amount = BigDecimal("10"),
            ),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNull()
        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, sourceUlId)
        assertThat(family).hasSize(1) // no successor order minted
    }

    // ── (h) final-gate item 1: negative amount bypassing DTO validation -> belt fires ──

    @Test
    fun `a negative amount that bypasses DTO validation is refused by the transferStock belt guard, source amount unchanged`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val targetUlId = seedUnitLoad(1L, plainType, 900_104L, "PARTIAL-TARGET-H-$s")
        val sourceUlId = seedUnitLoad(1L, plainType, 208L, "PARTIAL-SOURCE-H-$s")
        val sourceStockId = seedStock(sourceUlId, 1L, itemDataId = 9110L, itemNumber = "PARTIAL-SKU-H-$s", amount = BigDecimal("40"))

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        // Bypasses CompleteTransportOrderRequest's @field:Positive entirely (only enforced at the
        // REST layer -- this calls TaskService.complete directly, as every test in this class
        // does), so the ONLY thing stopping stock fabrication is StockService.transferStock's
        // own belt guard.
        assertThatThrownBy {
            taskService.complete(
                orderId,
                CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId, amount = BigDecimal("-5")),
                1L,
            )
        }.isInstanceOf(InventoryException.ValidationFailed::class.java)

        val order = repository.findByIdAndClient(orderId, 1L)!!
        assertThat(order.state).isEqualTo(OrderState.STARTED.code) // refusal precedes any mutation
        val source = stockUnitRepository.findById(sourceStockId)!!
        assertThat(source.amount).isEqualByComparingTo(BigDecimal("40")) // unchanged -- no fabrication
    }

    // ── (i) final-gate item 5d: full-amount confirm onto staging still CHAINS ──

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a confirm with amount equal to the full source stock onto a staging location still chains (falls through to the whole-UL path)`() {
        val s = System.nanoTime()
        val area = createArea("PT-I-AREA-$s", "STORAGE")
        val type = createLocationType("PT-I-LT-$s")
        val stagingCluster = createLocationCluster("PT-I-CLUSTER-$s")
        createStorageArea("PT-I-STAGING-$s", listOf(stagingCluster), transferStaging = true)
        val stagingLoc = createLocation("PT-I-STAGING-LOC-$s", type, area, clusterId = stagingCluster)
        val otherFinalTarget = createLocation("PT-I-OTHER-FINAL-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 209L, "PARTIAL-SOURCE-I-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9111L, itemNumber = "PARTIAL-SKU-I-$s", amount = BigDecimal("40"))

        val orderId = seedOrder(
            1L, sourceUlId, OrderState.STARTED.code,
            suggestedLocationId = otherFinalTarget.getLong("id"), suggestedLocationName = otherFinalTarget.getString("name"),
        )

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(
                destinationLocationId = stagingLoc.getLong("id"),
                destinationLocationName = stagingLoc.getString("name"),
                amount = BigDecimal("40"), // == the full source stock amount, not a genuine partial
            ),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNotNull() // unlike (g)'s genuine partial, this DOES chain
        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, sourceUlId)
        assertThat(family).hasSize(2) // predecessor + TRANSFER successor
    }

    // ── (j) final-gate item 5g: a LOCKED candidate at the destination is never reused ──

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a locked same-type unit load at the destination is never reused -- create-new happens instead`() {
        val s = System.nanoTime()
        val area = createArea("PT-J-AREA-$s", "STORAGE")
        val type = createLocationType("PT-J-LT-$s")
        val dest = createLocation("PT-J-DEST-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!

        // The ONLY candidate at the destination shares the source's own type (would normally be
        // the same-type reuse match) but is LOCKED -- resolveTargetUnitLoad's `lockType == 0`
        // filter must exclude it, forcing create-new instead of folding onto a locked pallet.
        val lockedUlId = seedUnitLoad(1L, plainType, dest.getLong("id"), "PT-J-LOCKED-$s")
        lockUnitLoad(lockedUlId)

        val sourceUlId = seedUnitLoad(1L, plainType, 210L, "PARTIAL-SOURCE-J-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9112L, itemNumber = "PARTIAL-SKU-J-$s", amount = BigDecimal("40"))
        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(
                destinationLocationId = dest.getLong("id"),
                destinationLocationName = dest.getString("name"),
                amount = BigDecimal("10"),
            ),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        val ulsAtDest = unitLoadRepository.findByStorageLocationId(dest.getLong("id"))
        assertThat(ulsAtDest).hasSize(2) // the locked one, untouched, plus a brand-new one
        val newUl = ulsAtDest.first { it.id != lockedUlId }
        assertThat(stockUnitRepository.findByUnitLoadId(newUl.id!!)).hasSize(1) // the moved amount landed on the NEW one
        assertThat(stockUnitRepository.findByUnitLoadId(lockedUlId)).isEmpty() // the locked one was never touched
    }
}

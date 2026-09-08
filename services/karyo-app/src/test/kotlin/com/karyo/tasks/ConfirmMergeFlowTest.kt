package com.karyo.tasks

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
 * PT16 (putaway-transport sprint, Task 3) — confirm-merge into an EXISTING unit load via the
 * new [com.karyo.inventory.api.spi.StockMover] SPI, exercised through the real
 * [TaskService.complete] entry point (`request.destinationUnitLoadId`).
 *
 * Fixture idiom: source/target unit loads and stock are seeded DIRECTLY via repositories
 * (mirrors `ChangeClientTest`/`TransferToCarrierTest` — a REST seed cannot create foreign-owner
 * rows, and this suite needs fine control over `UnitLoadType.aggregateStocks`), then
 * [TaskService] is called directly with a manually primed [TenantContext] (same pattern as
 * [TransportPauseFlowTest]/[TransferChainFlowTest] — `TaskService` methods take `clientId` as an
 * explicit parameter, but the SPIs underneath [TaskService.complete]'s merge branch read
 * [TenantContext] directly, so it must be primed by hand for a direct-service-call test). Only
 * the transfer-staging no-chain pin needs REAL layout fixtures, so that one test alone uses the
 * REST layout endpoints (mirrors [TransferChainFlowTest]'s `buildStagingFixture`).
 *
 * Clients 1 (ACME) and 2 (GLOBEX) exist via migration V1201.
 */
@QuarkusTest
class ConfirmMergeFlowTest {

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
    lateinit var tenantContext: TenantContext

    // ── Direct-repository fixture helpers ───────────────────────────────────

    @Transactional
    fun seedUnitLoad(owner: Long, type: UnitLoadType, locationId: Long, locationName: String): Long {
        val ul = UnitLoad().apply {
            clientId = owner
            labelId = "MERGE-UL-${System.nanoTime()}"
            unitLoadType = type
            storageLocationId = locationId
            storageLocationName = locationName
        }
        unitLoadRepository.persist(ul)
        return ul.id!!
    }

    @Transactional
    fun seedStock(unitLoadId: Long, owner: Long, itemDataId: Long, itemNumber: String, amount: BigDecimal): Long {
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
            orderNumber = "TO-MERGE-TEST-$seq"
            transportType = TransportType.MOVE
            this.unitLoadId = unitLoadId
            unitLoadLabel = "UL-MERGE-$seq"
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

    // ── Layout fixture helpers (mirrors TransferChainFlowTest — only the staging test needs these) ──

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

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    fun `merge into a unit load with a summable stock folds the amount, drains the source to DELETABLE, and finishes at the target's location`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val aggregatingType = unitLoadTypeRepository.findByName("Pick Bin")!!
        check(aggregatingType.aggregateStocks) { "fixture assumption: Pick Bin must aggregate stocks" }
        val targetUlId = seedUnitLoad(1L, aggregatingType, 900_001L, "MERGE-TARGET-A-$s")
        val existingTargetStockId = seedStock(targetUlId, 1L, itemDataId = 9001L, itemNumber = "MERGE-SKU-A-$s", amount = BigDecimal("15"))

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 100L, "MERGE-SOURCE-A-$s")
        val sourceStockId = seedStock(sourceUlId, 1L, itemDataId = 9001L, itemNumber = "MERGE-SKU-A-$s", amount = BigDecimal("40"))

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.finished).isNotNull()
        assertThat(completed.successorId).isNull()
        assertThat(completed.destinationLocationId).isEqualTo(900_001L)
        assertThat(completed.destinationLocationName).isEqualTo("MERGE-TARGET-A-$s")

        val foldedTarget = stockUnitRepository.findById(existingTargetStockId)!!
        assertThat(foldedTarget.amount).isEqualByComparingTo(BigDecimal("55"))

        val drainedSource = stockUnitRepository.findById(sourceStockId)!!
        assertThat(drainedSource.amount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(drainedSource.state).isEqualTo(StockState.DELETABLE.code)

        // Folded, not duplicated: still exactly one stock row on the aggregating target.
        assertThat(stockUnitRepository.findByUnitLoadId(targetUlId)).hasSize(1)
    }

    @Test
    fun `merge into a unit load with non-summable stock (non-aggregating type) creates a new stock row on the target`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val nonAggregatingType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        check(!nonAggregatingType.aggregateStocks) { "fixture assumption: Euro Pallet must NOT aggregate stocks" }
        val targetUlId = seedUnitLoad(1L, nonAggregatingType, 900_002L, "MERGE-TARGET-B-$s")
        val existingTargetStockId = seedStock(targetUlId, 1L, itemDataId = 9002L, itemNumber = "MERGE-SKU-B-$s", amount = BigDecimal("5"))

        val sourceUlId = seedUnitLoad(1L, nonAggregatingType, 101L, "MERGE-SOURCE-B-$s")
        val sourceStockId = seedStock(sourceUlId, 1L, itemDataId = 9002L, itemNumber = "MERGE-SKU-B-$s", amount = BigDecimal("40"))

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)

        val targetStocks = stockUnitRepository.findByUnitLoadId(targetUlId)
        assertThat(targetStocks).hasSize(2)
        val untouchedExisting = targetStocks.first { it.id == existingTargetStockId }
        assertThat(untouchedExisting.amount).isEqualByComparingTo(BigDecimal("5"))
        val newRow = targetStocks.first { it.id != existingTargetStockId }
        assertThat(newRow.amount).isEqualByComparingTo(BigDecimal("40"))

        val drainedSource = stockUnitRepository.findById(sourceStockId)!!
        assertThat(drainedSource.state).isEqualTo(StockState.DELETABLE.code)
    }

    @Test
    fun `merge confirm on a source unit load carrying two live stock units is refused with 409 MixedSourceLoad`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 102L, "MERGE-SOURCE-C-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9003L, itemNumber = "MERGE-SKU-C1-$s", amount = BigDecimal("10"))
        seedStock(sourceUlId, 1L, itemDataId = 9004L, itemNumber = "MERGE-SKU-C2-$s", amount = BigDecimal("10"))
        val targetUlId = seedUnitLoad(1L, plainType, 900_003L, "MERGE-TARGET-C-$s")

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        assertThatThrownBy {
            taskService.complete(orderId, CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId), 1L)
        }.isInstanceOf(TaskException.MixedSourceLoad::class.java)

        // Refusal precedes any mutation: the order is still STARTED, nothing moved.
        val order = repository.findByIdAndClient(orderId, 1L)!!
        assertThat(order.state).isEqualTo(OrderState.STARTED.code)
    }

    /**
     * Also pins the defect-burndown-6 Task 2 ruling: the merge's transfer stays on the AMBIENT
     * `StockMover.transferToUnitLoad` overload precisely so this 409 survives. Under the
     * explicit-`clientId` overload the synthetic owner-scoped context cannot see the foreign
     * target at all, and the refusal collapses into a 404 "unit load not found" -- a wrong
     * diagnostic for an OPS operator who can see the load they just scanned.
     */
    @Test
    fun `merge confirm onto a cross-owner target unit load is refused with 409 CrossOwner`() {
        val s = System.nanoTime()
        // An OPS principal is required to even SEE both owners' unit loads (an OWNER principal's
        // write scope would 404 on the foreign-owner target before the D1 cross-owner comparison
        // ever runs) -- same shape as TransferToCarrierTest's cross-owner nesting refusal.
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OPS

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 103L, "MERGE-SOURCE-D-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9005L, itemNumber = "MERGE-SKU-D-$s", amount = BigDecimal("10"))
        val targetUlId = seedUnitLoad(2L, plainType, 900_004L, "MERGE-TARGET-D-$s") // client 2 = GLOBEX

        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        assertThatThrownBy {
            taskService.complete(orderId, CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId), 1L)
        }.isInstanceOf(InventoryException.CrossOwner::class.java)

        // Refusal precedes any mutation: the order is still STARTED and the source stock is intact.
        val order = repository.findByIdAndClient(orderId, 1L)!!
        assertThat(order.state).isEqualTo(OrderState.STARTED.code)
        assertThat(stockUnitRepository.findByUnitLoadId(sourceUlId).single().amount)
            .isEqualByComparingTo(BigDecimal("10"))
    }

    @Test
    fun `supplying both destinationUnitLoadId and destinationLocationId is refused with 400`() {
        val s = System.nanoTime()
        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER

        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val sourceUlId = seedUnitLoad(1L, plainType, 105L, "MERGE-SOURCE-E-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9007L, itemNumber = "MERGE-SKU-E-$s", amount = BigDecimal("10"))
        val orderId = seedOrder(1L, sourceUlId, OrderState.STARTED.code)

        assertThatThrownBy {
            taskService.complete(
                orderId,
                CompleteTransportOrderRequest(destinationUnitLoadId = 999_999L, destinationLocationId = 888_888L),
                1L,
            )
        }.isInstanceOf(TaskException.ValidationFailed::class.java)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `merge confirm onto a unit load sitting in a transfer-staging area mints no chain successor`() {
        val s = System.nanoTime()
        val area = createArea("MRG-AREA-$s", "STORAGE")
        val type = createLocationType("MRG-LT-$s")
        val stagingCluster = createLocationCluster("MRG-CLUSTER-$s")
        createStorageArea("MRG-STAGING-$s", listOf(stagingCluster), transferStaging = true)
        val stagingLoc = createLocation("MRG-STAGING-LOC-$s", type, area, clusterId = stagingCluster)
        // A DIFFERENT real final target than the staging location the merge target sits in -- if
        // ChainContinuationService.maybeChain were (wrongly) invoked from the merge branch, this
        // mismatch + a staging destination is exactly the shape that mints a TRANSFER successor.
        val otherFinalTarget = createLocation("MRG-OTHER-FINAL-$s", type, area)

        tenantContext.clientId = 1L
        tenantContext.principalKind = PrincipalKind.OWNER
        val plainType = unitLoadTypeRepository.findByName("Euro Pallet")!!
        val targetUlId = seedUnitLoad(1L, plainType, stagingLoc.getLong("id"), stagingLoc.getString("name"))
        val sourceUlId = seedUnitLoad(1L, plainType, 104L, "MERGE-SOURCE-F-$s")
        seedStock(sourceUlId, 1L, itemDataId = 9006L, itemNumber = "MERGE-SKU-F-$s", amount = BigDecimal("12"))

        val orderId = seedOrder(
            1L, sourceUlId, OrderState.STARTED.code,
            suggestedLocationId = otherFinalTarget.getLong("id"), suggestedLocationName = otherFinalTarget.getString("name"),
        )

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(destinationUnitLoadId = targetUlId),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNull()
        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, sourceUlId)
        assertThat(family).hasSize(1) // no successor order minted
    }
}

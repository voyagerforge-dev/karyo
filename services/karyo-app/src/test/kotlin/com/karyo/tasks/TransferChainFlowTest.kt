package com.karyo.tasks

import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.orders.vo.OrderState
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

/**
 * PT15 (putaway-transport sprint, Task 2) — multi-hop transport chains via transfer-staging
 * areas. [ChainContinuationService][com.karyo.tasks.service.ChainContinuationService]'s
 * `maybeChain`, exercised through the real [TaskService.complete] entry point.
 *
 * Fixture pattern mirrors [com.karyo.app.PutawayFlowTest]'s area/cluster/storage-area REST
 * helpers (a transfer-staging [com.karyo.layout.domain.model.StorageArea] needs a REAL location
 * with a REAL cluster membership — [LocationLockPort.isTransferStaging][com.karyo.layout.spi.LocationLockPort.isTransferStaging]
 * is a real repository query, not a stub) plus [TransportPauseFlowTest]'s direct-repository
 * order seeding (no HTTP needed for the predecessor/successor themselves, since [TaskService]
 * methods take `clientId` as an explicit parameter).
 */
@QuarkusTest
class TransferChainFlowTest {

    @Inject
    lateinit var taskService: TaskService

    @Inject
    lateinit var repository: TransportOrderRepository

    @Inject
    lateinit var reservationRepository: LocationReservationRepository

    @Inject
    lateinit var unitLoadRepository: UnitLoadRepository

    @Inject
    lateinit var stockUnitRepository: StockUnitRepository

    @Inject
    lateinit var tenantContext: TenantContext

    // ── Layout fixture helpers (mirrors PutawayFlowTest) ───────────────────

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

    private fun seedUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":100,"storageLocationName":"RESERVE-A"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    /** Mirrors [PartialMoveFlowTest]'s `seedStock` -- a single live stock unit on [unitLoadId],
     *  needed so a chained successor has something real to inherit denorm fields from. */
    @Transactional
    fun seedStock(unitLoadId: Long, owner: Long, itemDataId: Long, itemNumber: String, lot: String, amount: java.math.BigDecimal): Long {
        val ul = unitLoadRepository.findById(unitLoadId)!!
        val su = StockUnit().apply {
            clientId = owner
            this.itemDataId = itemDataId
            itemDataNumber = itemNumber
            lotNumber = lot
            this.amount = amount
            state = StockState.ON_STOCK.code
            unitLoad = ul
        }
        stockUnitRepository.persist(su)
        return su.id!!
    }

    // ── TransportOrder fixture helper (mirrors TransportPauseFlowTest) ─────

    @Transactional
    fun seedOrder(
        clientId: Long,
        unitLoadId: Long,
        state: Int,
        suggestedLocationId: Long?,
        suggestedLocationName: String?,
        transportType: TransportType = TransportType.PUTAWAY,
        pausedAt: java.time.Instant? = null,
        // Coordinator-review fix: lets a REPLENISH staging-drop test simulate the R13-stamped
        // top-up quantity (createReplenishment's `order.amount = cmd.amount`) without going
        // through a real scan -- optional, defaults null so every existing caller is unaffected.
        amount: java.math.BigDecimal? = null,
    ): Long {
        val seq = System.nanoTime()
        val order = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "TO-CHAIN-TEST-$seq"
            this.transportType = transportType
            this.unitLoadId = unitLoadId
            unitLoadLabel = "UL-CHAIN-$seq"
            sourceLocationId = 1
            sourceLocationName = "DOCK-1"
            this.suggestedLocationId = suggestedLocationId
            this.suggestedLocationName = suggestedLocationName
            this.amount = amount
            this.state = state
            executorType = "HUMAN"
            this.pausedAt = pausedAt
        }
        repository.persist(order)
        return order.id!!
    }

    @Transactional
    fun seedReservation(transportOrderId: Long, locationId: Long) {
        val em = reservationRepository.getEntityManager()
        val reservation = com.karyo.layout.domain.model.LocationReservation().apply {
            this.locationId = locationId
            this.transportOrderId = transportOrderId
            percent = java.math.BigDecimal("100")
            expiresAt = java.time.Instant.now().plus(java.time.Duration.ofMinutes(10))
        }
        em.persist(reservation)
    }

    private data class StagingFixture(
        val stagingLocationId: Long,
        val stagingLocationName: String,
        val finalTargetId: Long,
        val finalTargetName: String,
    )

    /** Builds one transfer-staging area (with one location in it) plus one ordinary
     *  (non-staging) location to act as the chain's real final target. */
    private fun buildStagingFixture(s: Long): StagingFixture {
        val area = createArea("CHN-AREA-$s", "STORAGE")
        val type = createLocationType("CHN-LT-$s")
        val stagingCluster = createLocationCluster("CHN-CLUSTER-$s")
        createStorageArea("CHN-STAGING-$s", listOf(stagingCluster), transferStaging = true)
        val staging = createLocation("CHN-STAGING-LOC-$s", type, area, clusterId = stagingCluster)
        val finalTarget = createLocation("CHN-FINAL-LOC-$s", type, area)
        return StagingFixture(
            staging.getLong("id"), staging.getString("name"),
            finalTarget.getLong("id"), finalTarget.getString("name"),
        )
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `completing onto a transfer-staging location creates a TRANSFER successor, re-keys the reservation, and finishes the predecessor`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-A-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
        )
        seedReservation(predecessorId, fx.finalTargetId)
        tenantContext.clientId = 1L

        val completed = taskService.complete(
            predecessorId,
            CompleteTransportOrderRequest(destinationLocationId = fx.stagingLocationId, destinationLocationName = fx.stagingLocationName),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNotNull()

        val successor = repository.findByIdAndClient(completed.successorId!!, 1L)!!
        assertThat(successor.transportType).isEqualTo(TransportType.TRANSFER)
        assertThat(successor.state).isEqualTo(OrderState.RELEASED.code)
        assertThat(successor.unitLoadId).isEqualTo(ulId)
        assertThat(successor.sourceLocationId).isEqualTo(fx.stagingLocationId)
        assertThat(successor.sourceLocationName).isEqualTo(fx.stagingLocationName)
        assertThat(successor.suggestedLocationId).isEqualTo(fx.finalTargetId)
        assertThat(successor.suggestedLocationName).isEqualTo(fx.finalTargetName)

        // Reservation re-keyed: predecessor's released, successor holds the SAME location.
        assertThat(reservationRepository.findByTransportOrderId(predecessorId)).isEmpty()
        val successorReservations = reservationRepository.findByTransportOrderId(successor.id!!)
        assertThat(successorReservations).hasSize(1)
        assertThat(successorReservations.first().locationId).isEqualTo(fx.finalTargetId)
    }

    // ── Final-gate item 2: a chained successor must carry the predecessor's denorm fields ──

    /**
     * Final-gate fix: a successor is minted via `TransportOrder().apply {}` directly in
     * [com.karyo.tasks.service.ChainContinuationService.maybeChain], not through any of
     * [TaskService]'s own create* paths -- so it originally skipped
     * [com.karyo.tasks.service.ConfirmVariantService.denormalizeAtCreation] entirely, leaving
     * `itemDataId`/`itemDataNumber`/`lotNumber`/`amount`/`sourceStockUnitId` null on every
     * chained hop even though the predecessor's stock is known and unchanged at mint time (the
     * physical unit-load move already happened; only the layout reservation is still open).
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a chained successor carries the predecessor's stock denorm fields, not nulls`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-DENORM-$s")
        val stockId = seedStock(
            ulId, owner = 1L, itemDataId = 9201L, itemNumber = "CHAIN-SKU-$s",
            lot = "LOT-$s", amount = java.math.BigDecimal("30"),
        )

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
        )
        tenantContext.clientId = 1L

        val completed = taskService.complete(
            predecessorId,
            CompleteTransportOrderRequest(destinationLocationId = fx.stagingLocationId, destinationLocationName = fx.stagingLocationName),
            1L,
        )
        val successor = repository.findByIdAndClient(completed.successorId!!, 1L)!!

        assertThat(successor.itemDataId).isEqualTo(9201L)
        assertThat(successor.itemDataNumber).isEqualTo("CHAIN-SKU-$s")
        assertThat(successor.lotNumber).isEqualTo("LOT-$s")
        assertThat(successor.amount).isEqualByComparingTo(java.math.BigDecimal("30"))
        assertThat(successor.sourceStockUnitId).isEqualTo(stockId)
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `completing directly at the suggested final target creates no successor even if that location happens to be non-staging`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-B-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
        )
        tenantContext.clientId = 1L

        // No override -- destId defaults to order.suggestedLocationId, so destId == suggestedLocationId.
        val completed = taskService.complete(predecessorId, CompleteTransportOrderRequest(), 1L)

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNull()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `completing at an ordinary non-staging override location creates no successor`() {
        val s = System.nanoTime()
        val area = createArea("CHN2-AREA-$s", "STORAGE")
        val type = createLocationType("CHN2-LT-$s")
        val finalTarget = createLocation("CHN2-FINAL-$s", type, area)
        val otherOrdinary = createLocation("CHN2-OTHER-$s", type, area)
        val ulId = seedUnitLoad("UL-CHAIN-C-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = finalTarget.getLong("id"), suggestedLocationName = finalTarget.getString("name"),
        )
        tenantContext.clientId = 1L

        val completed = taskService.complete(
            predecessorId,
            CompleteTransportOrderRequest(
                destinationLocationId = otherOrdinary.getLong("id"),
                destinationLocationName = otherOrdinary.getString("name"),
            ),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNull()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `completing the successor at the final target ends the chain -- no third order`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-D-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
        )
        tenantContext.clientId = 1L
        val completedPredecessor = taskService.complete(
            predecessorId,
            CompleteTransportOrderRequest(destinationLocationId = fx.stagingLocationId, destinationLocationName = fx.stagingLocationName),
            1L,
        )
        val successorId = completedPredecessor.successorId!!

        // Drive the successor STARTED, then complete it exactly at its own suggestion (the real
        // final target) -- destId == suggestedLocationId, so the chain must NOT continue.
        markStarted(successorId)
        val completedSuccessor = taskService.complete(successorId, CompleteTransportOrderRequest(), 1L)

        assertThat(completedSuccessor.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completedSuccessor.successorId).isNull()

        // Exactly two orders exist for this unit load: predecessor + successor, no third hop.
        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, ulId)
        assertThat(family).hasSize(2)
    }

    @Transactional
    fun markStarted(id: Long) {
        val order = repository.findById(id)!!
        order.state = OrderState.STARTED.code
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `cancel of a successor releases only its own reservation`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-E-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
        )
        seedReservation(predecessorId, fx.finalTargetId)
        tenantContext.clientId = 1L
        val completed = taskService.complete(
            predecessorId,
            CompleteTransportOrderRequest(destinationLocationId = fx.stagingLocationId, destinationLocationName = fx.stagingLocationName),
            1L,
        )
        val successorId = completed.successorId!!
        assertThat(reservationRepository.findByTransportOrderId(successorId)).hasSize(1)

        val canceled = taskService.cancel(successorId, 1L)
        assertThat(canceled.state).isEqualTo(OrderState.CANCELED.code)

        // Only the successor's own reservation is released -- the predecessor's was already
        // released (and re-keyed away) at chain time, not touched again by this cancel.
        assertThat(reservationRepository.findByTransportOrderId(successorId)).isEmpty()
        assertThat(reservationRepository.findByTransportOrderId(predecessorId)).isEmpty()
    }

    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a paused predecessor cannot complete, so no chain is ever created`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-F-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
            pausedAt = java.time.Instant.now(),
        )
        tenantContext.clientId = 1L

        assertThatThrownBy {
            taskService.complete(
                predecessorId,
                CompleteTransportOrderRequest(destinationLocationId = fx.stagingLocationId, destinationLocationName = fx.stagingLocationName),
                1L,
            )
        }.isInstanceOf(TaskException.TransportPaused::class.java)

        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, ulId)
        assertThat(family).hasSize(1) // the predecessor only -- no successor was minted
        assertThat(family.first().successorId).isNull()
    }

    // ── Review fix: chains are RECURSIVE, not one-hop-only ─────────────────

    /**
     * Controller ruling on review: [com.karyo.tasks.service.ChainContinuationService.maybeChain]
     * runs on every completion, predecessor or successor alike, so a chain can walk through MORE
     * than one staging waypoint before reaching its final target. Two independent transfer-staging
     * areas (A then B), same real final target throughout: predecessor -> S1 (via staging A) ->
     * S2 (via staging B) -> FINISHED at the final target, no fourth order. Each hop re-keys the
     * reservation onto itself and stamps its own predecessor's `successorId`.
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `a chain can walk through two staging waypoints before reaching its final target -- no fourth order`() {
        val s = System.nanoTime()
        val area = createArea("CHN3-AREA-$s", "STORAGE")
        val type = createLocationType("CHN3-LT-$s")

        val clusterA = createLocationCluster("CHN3-CLUSTER-A-$s")
        createStorageArea("CHN3-STAGING-A-$s", listOf(clusterA), transferStaging = true)
        val stagingA = createLocation("CHN3-STAGING-A-LOC-$s", type, area, clusterId = clusterA)

        val clusterB = createLocationCluster("CHN3-CLUSTER-B-$s")
        createStorageArea("CHN3-STAGING-B-$s", listOf(clusterB), transferStaging = true)
        val stagingB = createLocation("CHN3-STAGING-B-LOC-$s", type, area, clusterId = clusterB)

        val finalTarget = createLocation("CHN3-FINAL-$s", type, area)
        val ulId = seedUnitLoad("UL-CHAIN-G-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = finalTarget.getLong("id"), suggestedLocationName = finalTarget.getString("name"),
        )
        seedReservation(predecessorId, finalTarget.getLong("id"))
        tenantContext.clientId = 1L

        // Hop 1: predecessor -> staging A -> mints S1.
        val completedPredecessor = taskService.complete(
            predecessorId,
            CompleteTransportOrderRequest(destinationLocationId = stagingA.getLong("id"), destinationLocationName = stagingA.getString("name")),
            1L,
        )
        val s1Id = completedPredecessor.successorId!!
        val s1 = repository.findByIdAndClient(s1Id, 1L)!!
        assertThat(s1.transportType).isEqualTo(TransportType.TRANSFER)
        assertThat(s1.state).isEqualTo(OrderState.RELEASED.code)
        assertThat(s1.sourceLocationId).isEqualTo(stagingA.getLong("id"))
        assertThat(s1.suggestedLocationId).isEqualTo(finalTarget.getLong("id"))
        assertThat(reservationRepository.findByTransportOrderId(predecessorId)).isEmpty()
        val s1Reservations = reservationRepository.findByTransportOrderId(s1Id)
        assertThat(s1Reservations).hasSize(1)
        assertThat(s1Reservations.first().locationId).isEqualTo(finalTarget.getLong("id"))

        // Hop 2: S1 -> staging B -> mints S2 (the THIRD order overall). S1 itself finishes,
        // carrying its own successorId forward.
        markStarted(s1Id)
        val completedS1 = taskService.complete(
            s1Id,
            CompleteTransportOrderRequest(destinationLocationId = stagingB.getLong("id"), destinationLocationName = stagingB.getString("name")),
            1L,
        )
        assertThat(completedS1.state).isEqualTo(OrderState.FINISHED.code)
        val s2Id = completedS1.successorId!!
        val s2 = repository.findByIdAndClient(s2Id, 1L)!!
        assertThat(s2.transportType).isEqualTo(TransportType.TRANSFER)
        assertThat(s2.state).isEqualTo(OrderState.RELEASED.code)
        assertThat(s2.sourceLocationId).isEqualTo(stagingB.getLong("id"))
        // The final target is inherited unchanged across every hop.
        assertThat(s2.suggestedLocationId).isEqualTo(finalTarget.getLong("id"))
        assertThat(reservationRepository.findByTransportOrderId(s1Id)).isEmpty()
        val s2Reservations = reservationRepository.findByTransportOrderId(s2Id)
        assertThat(s2Reservations).hasSize(1)
        assertThat(s2Reservations.first().locationId).isEqualTo(finalTarget.getLong("id"))

        // Hop 3: S2 completes AT the final target -- destId == suggestedLocationId, chain ends.
        markStarted(s2Id)
        val completedS2 = taskService.complete(s2Id, CompleteTransportOrderRequest(), 1L)
        assertThat(completedS2.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completedS2.successorId).isNull()

        // Exactly three orders exist for this unit load: predecessor, S1, S2 -- no fourth hop.
        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, ulId)
        assertThat(family).hasSize(3)
    }

    // ── Review fix: early-return ordering pin ───────────────────────────────

    /**
     * Corner case flagged on review: an order whose FINAL TARGET happens to sit in a
     * transfer-staging area too. Completing directly AT that target must still create no
     * successor -- [com.karyo.tasks.service.ChainContinuationService.maybeChain] checks
     * `finalTarget == destId` BEFORE `isTransferStaging(destId)`, so reaching the real final
     * target always ends the chain regardless of what that location's own area is flagged.
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `completing at a final target whose own area is transfer-staging still creates no successor`() {
        val s = System.nanoTime()
        val area = createArea("CHN4-AREA-$s", "STORAGE")
        val type = createLocationType("CHN4-LT-$s")
        val cluster = createLocationCluster("CHN4-CLUSTER-$s")
        createStorageArea("CHN4-STAGING-$s", listOf(cluster), transferStaging = true)
        // The "final target" itself is a member of a transfer-staging area.
        val finalTargetInStagingArea = createLocation("CHN4-FINAL-IN-STAGING-$s", type, area, clusterId = cluster)
        val ulId = seedUnitLoad("UL-CHAIN-H-$s")

        val predecessorId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = finalTargetInStagingArea.getLong("id"),
            suggestedLocationName = finalTargetInStagingArea.getString("name"),
        )
        tenantContext.clientId = 1L

        // No override -- destId defaults to order.suggestedLocationId, so destId == suggestedLocationId
        // even though that location's area IS transfer-staging.
        val completed = taskService.complete(predecessorId, CompleteTransportOrderRequest(), 1L)

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.successorId).isNull()
        val family = repository.list("clientId = ?1 and unitLoadId = ?2", 1L, ulId)
        assertThat(family).hasSize(1)
    }

    // ── Coordinator-review fix (Task 2 follow-up): staging-drop pin ────────

    /**
     * IMPORTANT-2 regression pin: a REPLENISH order minted with an R13 top-up amount, completed
     * with an EXPLICIT `destinationLocationId` at a transfer-staging location, must take the
     * ORDINARY whole-UL path and still chain — the R13 default-amount rule in
     * [TaskService.complete] must NOT apply here (see that function's KDoc, "R13 default-amount
     * rule"). Before the fix, an amount-less completion at staging silently became a genuine
     * partial (`order.amount` < the source stock's full amount), and partials never chain
     * ([com.karyo.tasks.service.ConfirmVariantService.completePartialIfApplicable]'s own KDoc) --
     * stranding the moved quantity at staging with the order already FINISHED, no successor to
     * carry it onward.
     */
    @Test
    @TestSecurity(
        user = "manager",
        roles = ["inventory-read", "inventory-write", "layout-read", "layout-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "1"), Claim(key = "tenant_code", value = "ACME")])
    fun `R13 staging-drop pin -- an explicit destinationLocationId skips the default, whole-UL relocates, and still chains`() {
        val s = System.nanoTime()
        val fx = buildStagingFixture(s)
        val ulId = seedUnitLoad("UL-CHAIN-R13-$s")
        // Single live stock of 40, but the order was minted with a top-up amount of only 10 --
        // if the default-amount rule wrongly applied here, this would look like a genuine partial
        // (10 < 40) instead of the whole-UL move it must be.
        val stockId = seedStock(
            ulId, owner = 1L, itemDataId = 9301L, itemNumber = "CHAIN-R13-SKU-$s",
            lot = "LOT-R13-$s", amount = java.math.BigDecimal("40"),
        )

        val orderId = seedOrder(
            clientId = 1L, unitLoadId = ulId, state = OrderState.STARTED.code,
            suggestedLocationId = fx.finalTargetId, suggestedLocationName = fx.finalTargetName,
            transportType = TransportType.REPLENISH, amount = java.math.BigDecimal("10"),
        )
        tenantContext.clientId = 1L

        val completed = taskService.complete(
            orderId,
            CompleteTransportOrderRequest(destinationLocationId = fx.stagingLocationId, destinationLocationName = fx.stagingLocationName),
            1L,
        )

        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        // Task 3 (defect-burndown-4, row 6): the whole-UL path now records the LIVE
        // single-live-stock amount actually relocated (40), not the R13 top-up snapshot
        // (order.amount = 10) -- before the fix this asserted 10 even though the whole 40-unit
        // stock physically moved.
        assertThat(completed.confirmedAmount).isEqualByComparingTo(java.math.BigDecimal("40"))
        assertThat(completed.successorId).isNotNull() // R13 default did NOT suppress the chain

        val successor = repository.findByIdAndClient(completed.successorId!!, 1L)!!
        assertThat(successor.transportType).isEqualTo(TransportType.TRANSFER)
        assertThat(successor.unitLoadId).isEqualTo(ulId)

        // Whole-UL relocation, not a partial drain -- the source stock itself is untouched.
        val sourceStock = stockUnitRepository.findByUnitLoadId(ulId).first { it.id == stockId }
        assertThat(sourceStock.amount).isEqualByComparingTo(java.math.BigDecimal("40"))
    }
}

package com.karyo.tasks

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
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * PT18 — orthogonal transport-order pause. Mirrors
 * [com.karyo.orders.service.GoodsReceiptService]'s B7 pause/resume shape (fail-loud 409
 * symmetry both ways): `state` NEVER moves on pause/resume, `assign`/`start`/`complete` refuse
 * 409 while paused, `cancel` deliberately does not. The pausable window here is CREATED/
 * RELEASED/RESERVED/STARTED — wider than GoodsReceipt's CREATED/STARTED — because the
 * queue-and-claim lifecycle has two extra parking points (queued, claimed-but-not-started).
 *
 * Most cases seed a [TransportOrder] directly via the repository (same pattern as
 * [com.karyo.work.TransportWorkProviderTest] / [TransportOrderReplenishmentRepositoryTest])
 * and drive [TaskService] directly — no HTTP, no auth needed, since the service methods take
 * `clientId` as an explicit parameter rather than reading `TenantContext`. The one exception is
 * the STARTED→complete success path, which needs a REAL unit load (the real
 * [com.karyo.inventory.api.spi.UnitLoadMover] bean requires one to exist) — seeded via REST,
 * same pattern as [TransportOrderPortTest].
 */
@QuarkusTest
class TransportPauseFlowTest {

    @Inject
    lateinit var taskService: TaskService

    @Inject
    lateinit var repository: TransportOrderRepository

    @Inject
    lateinit var tenantContext: TenantContext

    @Transactional
    fun seedOrder(
        clientId: Long,
        state: Int,
        operatorId: String? = null,
        suggestedLocationId: Long? = 20L,
        suggestedLocationName: String? = "B-02",
    ): Long {
        val seq = System.nanoTime()
        val order = TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "TO-PAUSE-TEST-$seq"
            transportType = TransportType.MOVE
            unitLoadId = 1
            unitLoadLabel = "UL-1"
            sourceLocationId = 1
            sourceLocationName = "A-01"
            this.suggestedLocationId = suggestedLocationId
            this.suggestedLocationName = suggestedLocationName
            this.state = state
            this.operatorId = operatorId
            executorType = "HUMAN"
        }
        repository.persist(order)
        return order.id!!
    }

    // ── pause a RELEASED order blocks assign, resume unblocks it ──────────

    @Test
    fun `pausing a RELEASED order blocks assign with TransportPaused, resume unblocks it`() {
        val clientId = System.nanoTime()
        val id = seedOrder(clientId, OrderState.RELEASED.code)

        val paused = taskService.pause(id, clientId)
        assertThat(paused.pausedAt).isNotNull()
        assertThat(paused.state).isEqualTo(OrderState.RELEASED.code) // state NEVER moves

        assertThatThrownBy { taskService.assign(id, "alice", clientId) }
            .isInstanceOf(TaskException.TransportPaused::class.java)

        val resumed = taskService.resume(id, clientId)
        assertThat(resumed.pausedAt).isNull()

        val assigned = taskService.assign(id, "alice", clientId)
        assertThat(assigned.state).isEqualTo(OrderState.RESERVED.code)
        assertThat(assigned.operatorId).isEqualTo("alice")
    }

    // ── pause a RESERVED order blocks start, resume unblocks it ───────────

    @Test
    fun `pausing a RESERVED order blocks start with TransportPaused, resume unblocks it`() {
        val clientId = System.nanoTime()
        val id = seedOrder(clientId, OrderState.RESERVED.code, operatorId = "alice")

        taskService.pause(id, clientId)

        assertThatThrownBy { taskService.start(id, clientId) }
            .isInstanceOf(TaskException.TransportPaused::class.java)

        taskService.resume(id, clientId)

        val started = taskService.start(id, clientId)
        assertThat(started.state).isEqualTo(OrderState.STARTED.code)
        assertThat(started.started).isNotNull()
    }

    // ── pause a STARTED order blocks complete; resume + complete succeeds ─

    private fun seedUnitLoad(label: String): Long =
        given().contentType(ContentType.JSON)
            .body(
                """{"labelId":"$label","unitLoadTypeId":1,""" +
                    """"storageLocationId":100,"storageLocationName":"RESERVE-A"}""",
            )
            .`when`().post("/api/v1/unit-loads")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id")

    @Test
    @TestSecurity(
        user = "op-pause-complete",
        roles = ["inventory-read", "inventory-write", "task-read", "task-write", "MANAGER"],
    )
    @OidcSecurity(claims = [Claim(key = "client_id", value = "8419"), Claim(key = "tenant_code", value = "ACME")])
    fun `pausing a STARTED order blocks complete with TransportPaused, resume then complete succeeds`() {
        val clientId = 8419L
        val ulId = seedUnitLoad("UL-PAUSE-COMPLETE-${System.nanoTime()}")
        val id = seedOrder(
            clientId,
            OrderState.STARTED.code,
            operatorId = "alice",
        )
        // Point the seeded order at the real unit load so complete()'s real UnitLoadMover succeeds.
        pointAtUnitLoad(id, ulId)
        // DefaultUnitLoadMover reads TenantContext directly (it's @RequestScoped, only populated
        // by TenantFilter during HTTP requests) -- prime it for this direct CDI call, same pattern
        // as TransportOrderPortTest.
        tenantContext.clientId = clientId

        taskService.pause(id, clientId)

        assertThatThrownBy { taskService.complete(id, CompleteTransportOrderRequest(), clientId) }
            .isInstanceOf(TaskException.TransportPaused::class.java)

        taskService.resume(id, clientId)

        val completed = taskService.complete(id, CompleteTransportOrderRequest(), clientId)
        assertThat(completed.state).isEqualTo(OrderState.FINISHED.code)
        assertThat(completed.pausedAt).isNull()
        assertThat(completed.finished).isNotNull()
    }

    @Transactional
    fun pointAtUnitLoad(orderId: Long, unitLoadId: Long) {
        val order = repository.findById(orderId)!!
        order.unitLoadId = unitLoadId
    }

    // ── double-pause / resume-unpaused fail-loud symmetry ──────────────────

    @Test
    fun `double pause is a TransportPauseConflict 409, resume of an unpaused order is a TransportPauseConflict 409`() {
        val clientId = System.nanoTime()
        val id = seedOrder(clientId, OrderState.RELEASED.code)

        taskService.pause(id, clientId)
        assertThatThrownBy { taskService.pause(id, clientId) }
            .isInstanceOf(TaskException.TransportPauseConflict::class.java)

        taskService.resume(id, clientId)
        assertThatThrownBy { taskService.resume(id, clientId) }
            .isInstanceOf(TaskException.TransportPauseConflict::class.java)
    }

    /**
     * Pins the [com.karyo.tasks.service.TaskService] `PAUSABLE_STATES` whitelist boundary
     * explicitly: BOTH terminal states (CANCELED and FINISHED, not just one) are outside the
     * pausable window. Looped rather than duplicated per-state so the boundary reads as one
     * intentional assertion, not two coincidentally-similar tests.
     */
    @Test
    fun `pausing a CANCELED or a FINISHED order is a TransportPauseConflict 409 (terminal states are outside the pausable window)`() {
        val clientId = System.nanoTime()

        listOf(OrderState.CANCELED, OrderState.FINISHED).forEach { terminal ->
            val id = seedOrder(clientId, terminal.code)
            assertThatThrownBy { taskService.pause(id, clientId) }
                .describedAs("pausing a %s order", terminal)
                .isInstanceOf(TaskException.TransportPauseConflict::class.java)
        }
    }

    // ── cancel of a paused order deliberately SUCCEEDS ─────────────────────

    @Test
    fun `cancel of a paused order succeeds`() {
        val clientId = System.nanoTime()
        val id = seedOrder(clientId, OrderState.RELEASED.code)

        taskService.pause(id, clientId)

        val canceled = taskService.cancel(id, clientId)
        assertThat(canceled.state).isEqualTo(OrderState.CANCELED.code)
    }
}

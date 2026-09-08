package com.karyo.work

import com.karyo.security.TenantContext
import com.karyo.tasks.exception.TaskException
import com.karyo.tasks.messaging.TransportWorkProvider
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.service.TaskService
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

@QuarkusTest
class TransportWorkProviderTest {
    @Inject lateinit var provider: TransportWorkProvider
    @Inject lateinit var repo: TransportOrderRepository
    @Inject lateinit var tenantContext: TenantContext
    @Inject lateinit var taskService: TaskService

    @Transactional
    fun seedReleasedMove(clientId: Long, pausedAt: Instant? = null): Long {
        // Build a RELEASED MOVE TransportOrder directly via the repository to avoid HTTP setup.
        val o = com.karyo.tasks.domain.model.TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "WI-TEST-${clientId}-1"
            transportType = com.karyo.tasks.vo.TransportType.MOVE
            unitLoadId = 1; unitLoadLabel = "UL-1"
            sourceLocationId = 1; sourceLocationName = "A-01"
            state = com.karyo.orders.vo.OrderState.RELEASED.code
            prio = 50
            executorType = "HUMAN"
            this.pausedAt = pausedAt
        }
        repo.persist(o)
        return o.id!!
    }

    @Test
    fun `listOpen surfaces a RELEASED move, claim sets operator and removes it from the pool`() {
        val clientId = 8200L
        val id = seedReleasedMove(clientId)
        tenantContext.clientId = clientId  // prime @RequestScoped context for direct SPI calls

        val open = provider.listOpen(WorkFilter(setOf(WorkType.MOVE)))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.MOVE, id))

        val claimed = provider.claim(WorkRef(WorkType.MOVE, id), "alice")
        assertThat(claimed.state).isEqualTo(WorkState.CLAIMED)
        assertThat(claimed.claimedBy).isEqualTo("alice")
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.MOVE))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.MOVE, id))
        assertThat(provider.listClaimedBy("alice").map { it.ref }).contains(WorkRef(WorkType.MOVE, id))

        provider.release(WorkRef(WorkType.MOVE, id), "alice", false)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.MOVE))).map { it.ref })
            .contains(WorkRef(WorkType.MOVE, id))
    }

    @Transactional
    fun seedReservedMove(clientId: Long, operatorId: String, pausedAt: Instant? = null): Long {
        // Seed a RESERVED MOVE order with an existing claim, bypassing assign() to stay in-transaction.
        val o = com.karyo.tasks.domain.model.TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "WI-TEST-${clientId}-2"
            transportType = com.karyo.tasks.vo.TransportType.MOVE
            unitLoadId = 1; unitLoadLabel = "UL-1"
            sourceLocationId = 1; sourceLocationName = "A-01"
            state = com.karyo.orders.vo.OrderState.RESERVED.code
            this.operatorId = operatorId
            prio = 50
            executorType = "HUMAN"
            this.pausedAt = pausedAt
        }
        repo.persist(o)
        return o.id!!
    }

    @Test
    fun `release throws ValidationFailed not IllegalArgumentException when operator does not match`() {
        val clientId = 8204L
        val id = seedReservedMove(clientId, "alice")
        tenantContext.clientId = clientId

        assertThatThrownBy { taskService.release(id, "mallory", clientId) }
            .isInstanceOf(TaskException.ValidationFailed::class.java)
            .hasMessageContaining("claimed by a different operator")
    }

    @Test
    fun `a paused transport order is absent from both listOpen and listClaimedBy`() {
        val clientId = 8210L
        val openId = seedReleasedMove(clientId, pausedAt = Instant.now())
        val claimedId = seedReservedMove(clientId, "carol", pausedAt = Instant.now())
        tenantContext.clientId = clientId

        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.MOVE))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.MOVE, openId))
        assertThat(provider.listClaimedBy("carol").map { it.ref })
            .doesNotContain(WorkRef(WorkType.MOVE, claimedId))
    }

    // ── claim() translation — mirrors ReceivingWorkProviderTest's shape ────

    @Test
    fun `claim of an already-claimed transport order throws WorkClaimConflictException`() {
        val clientId = System.nanoTime()
        val id = seedReservedMove(clientId, "bob")
        tenantContext.clientId = clientId

        assertThatThrownBy { provider.claim(WorkRef(WorkType.MOVE, id), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$id")
    }

    @Test
    fun `claim of a nonexistent id throws WorkClaimConflictException (dispatch loop delete-race path)`() {
        val clientId = System.nanoTime()
        tenantContext.clientId = clientId
        val nonExistentId = Long.MAX_VALUE - 3 // guaranteed not in DB
        assertThatThrownBy { provider.claim(WorkRef(WorkType.MOVE, nonExistentId), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$nonExistentId")
    }

    /**
     * PT18 day-one race-closure pin: before the [TransportWorkProvider.claim] refactor, this
     * exact scenario (RELEASED + paused) could be claimed, because the provider's own inline
     * guard never checked [com.karyo.tasks.domain.model.TransportOrder.pausedAt] — only
     * [TaskService.assign] did, and `claim` never called it until the guard failed first. Now
     * `claim` delegates straight to `assign`, so the pause guard bites on the claim path too.
     */
    @Test
    fun `claim of a paused transport order throws WorkClaimConflictException`() {
        val clientId = System.nanoTime()
        val id = seedReleasedMove(clientId, pausedAt = Instant.now())
        tenantContext.clientId = clientId

        assertThatThrownBy { provider.claim(WorkRef(WorkType.MOVE, id), "alice") }
            .isInstanceOf(WorkClaimConflictException::class.java)
            .hasMessageContaining("$id")
    }

    // ── PT15: TRANSFER round-trip ───────────────────────────────────────────

    @Transactional
    fun seedReleasedTransfer(clientId: Long): Long {
        val o = com.karyo.tasks.domain.model.TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "WI-TEST-$clientId-TRANSFER"
            transportType = com.karyo.tasks.vo.TransportType.TRANSFER
            unitLoadId = 1; unitLoadLabel = "UL-1"
            sourceLocationId = 1; sourceLocationName = "STAGING-1"
            state = com.karyo.orders.vo.OrderState.RELEASED.code
            prio = 50
            executorType = "HUMAN"
        }
        repo.persist(o)
        return o.id!!
    }

    /**
     * The "500-landmine" pin: before [WorkType.TRANSFER] existed, [TransportWorkProvider.toWorkItem]'s
     * `WorkType.valueOf(transportType.name)` threw `IllegalArgumentException` for a TRANSFER order --
     * a bare 500 the moment one was claimed and then listed back via [TransportWorkProvider.listClaimedBy].
     * A claimed TRANSFER order must round-trip through open -> claim -> listClaimedBy -> release
     * exactly like every other transport type.
     */
    @Test
    fun `a claimed TRANSFER order round-trips through listOpen, claim, and listClaimedBy without crashing`() {
        val clientId = 8220L
        val id = seedReleasedTransfer(clientId)
        tenantContext.clientId = clientId

        val open = provider.listOpen(WorkFilter(setOf(WorkType.TRANSFER)))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.TRANSFER, id))

        val claimed = provider.claim(WorkRef(WorkType.TRANSFER, id), "alice")
        assertThat(claimed.state).isEqualTo(WorkState.CLAIMED)
        assertThat(claimed.ref).isEqualTo(WorkRef(WorkType.TRANSFER, id))

        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.TRANSFER))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.TRANSFER, id))
        assertThat(provider.listClaimedBy("alice").map { it.ref }).contains(WorkRef(WorkType.TRANSFER, id))

        provider.release(WorkRef(WorkType.TRANSFER, id), "alice", false)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.TRANSFER))).map { it.ref })
            .contains(WorkRef(WorkType.TRANSFER, id))
    }

    // ── Cross-docking sprint (Task 3 fix-round): CROSS_DOCK joins the work-inbox ───────────

    @Transactional
    fun seedReleasedCrossDock(clientId: Long): Long {
        val o = com.karyo.tasks.domain.model.TransportOrder().apply {
            this.clientId = clientId
            orderNumber = "WI-TEST-$clientId-XD"
            transportType = com.karyo.tasks.vo.TransportType.CROSS_DOCK
            unitLoadId = 1; unitLoadLabel = "UL-1"
            sourceLocationId = 1; sourceLocationName = "DOCK-1"
            destinationLocationId = 2; destinationLocationName = "XD-STAGE-01"
            state = com.karyo.orders.vo.OrderState.RELEASED.code
            prio = 50
            executorType = "HUMAN"
        }
        repo.persist(o)
        return o.id!!
    }

    /**
     * Cross-docking sprint fix-round: mirrors the TRANSFER "500-landmine" pin above. Before
     * [WorkType.CROSS_DOCK] existed and `workTypes()` included it, a CROSS_DOCK transport order
     * was invisible to [TransportWorkProvider.listOpen]/[listClaimedBy] (excluded from
     * `workTypes()`), so it never reached [TransportWorkProvider.toWorkItem]'s
     * `WorkType.valueOf(transportType.name)` at all -- a dead end for the operator floor, not a
     * crash. This proves the fix: CROSS_DOCK now round-trips through the SAME work-inbox surface
     * every other transport type uses (open -> claim -> listClaimedBy -> release), with no new
     * pane, no new claim path, and (per `toWorkItem`'s existing generic `valueOf` mapping) no new
     * code needed beyond the two `workTypes()`/`WorkType` additions.
     */
    @Test
    fun `a claimed CROSS_DOCK order round-trips through listOpen, claim, and listClaimedBy without crashing`() {
        val clientId = 8230L
        val id = seedReleasedCrossDock(clientId)
        tenantContext.clientId = clientId

        val open = provider.listOpen(WorkFilter(setOf(WorkType.CROSS_DOCK)))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.CROSS_DOCK, id))

        val claimed = provider.claim(WorkRef(WorkType.CROSS_DOCK, id), "alice")
        assertThat(claimed.state).isEqualTo(WorkState.CLAIMED)
        assertThat(claimed.ref).isEqualTo(WorkRef(WorkType.CROSS_DOCK, id))

        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.CROSS_DOCK))).map { it.ref })
            .doesNotContain(WorkRef(WorkType.CROSS_DOCK, id))
        assertThat(provider.listClaimedBy("alice").map { it.ref }).contains(WorkRef(WorkType.CROSS_DOCK, id))

        provider.release(WorkRef(WorkType.CROSS_DOCK, id), "alice", false)
        assertThat(provider.listOpen(WorkFilter(setOf(WorkType.CROSS_DOCK))).map { it.ref })
            .contains(WorkRef(WorkType.CROSS_DOCK, id))
    }

    /**
     * The specific "appears in the available list" assertion the fix-round review asked for,
     * isolated from the claim/release round-trip above: a CROSS_DOCK order surfaces in the
     * general (unfiltered) [TransportWorkProvider.listOpen] result, exactly like every other
     * transport type -- proving `workTypes()` now includes it, not just this test's own explicit
     * `WorkFilter(setOf(WorkType.CROSS_DOCK))` filter.
     */
    @Test
    fun `a RELEASED CROSS_DOCK order appears in the unfiltered available work list`() {
        val clientId = 8231L
        val id = seedReleasedCrossDock(clientId)
        tenantContext.clientId = clientId

        val open = provider.listOpen(WorkFilter(null))
        assertThat(open.map { it.ref }).contains(WorkRef(WorkType.CROSS_DOCK, id))
        assertThat(open.first { it.ref == WorkRef(WorkType.CROSS_DOCK, id) }.workType)
            .isEqualTo(WorkType.CROSS_DOCK)
    }
}

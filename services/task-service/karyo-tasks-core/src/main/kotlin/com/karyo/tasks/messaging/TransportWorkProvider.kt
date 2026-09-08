package com.karyo.tasks.messaging

import com.karyo.security.TenantContext
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.exception.TaskException
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.service.TaskService
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.spi.WorkProvider
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class TransportWorkProvider(
    private val repository: TransportOrderRepository,
    private val taskService: TaskService,
    private val tenantContext: TenantContext,
) : WorkProvider {

    override fun workTypes(): Set<WorkType> =
        setOf(WorkType.PUTAWAY, WorkType.MOVE, WorkType.REPLENISH, WorkType.TRANSFER, WorkType.CROSS_DOCK)

    override fun listOpen(filter: WorkFilter): List<WorkItem> {
        val names = (filter.types?.intersect(workTypes()) ?: workTypes()).map { it.name }.toSet()
        return repository.findClaimable(tenantContext.clientId, names).map { it.toWorkItem(WorkState.OPEN) }
    }

    override fun listClaimedBy(operatorId: String): List<WorkItem> =
        repository.findClaimedBy(tenantContext.clientId, operatorId).map { it.toWorkItem(WorkState.CLAIMED) }

    /**
     * Claims via [TaskService.assign] (the source of truth), then re-reads the entity for the
     * [WorkItem] shape. Delegate-and-translate shape mirroring
     * [com.karyo.orders.messaging.ReceivingWorkProvider.claim] — PT18 refactor: this used to
     * duplicate [TaskService.assign]'s state/operator guard here first, which meant the pause
     * guard added to [TaskService.assign] would have needed a SECOND copy here to actually bite
     * on the claim path (the exact trap a GR WORKLIST row was filed for). Now the guard lives
     * ONLY in [TaskService.assign]; split into [claimOrConflict] + [findClaimedOrConflict] so
     * neither function's throw count trips detekt's `ThrowsCount` (max 2).
     */
    @Transactional
    override fun claim(ref: WorkRef, operatorId: String): WorkItem {
        claimOrConflict(ref, operatorId)
        return findClaimedOrConflict(ref)
    }

    /**
     * [TaskService.assign]'s failure modes are translated to [WorkClaimConflictException] — the
     * exact type `WorkDispatchService.getNext`'s race-skip loop depends on:
     * [TaskException.InvalidTransition] (not RELEASED — already taken, or a race lost),
     * [TaskException.TransportPaused] (parked, not floor work), and [TaskException.NotFound]
     * (the delete-race path: the order vanished between the pool read and this claim). Three
     * catch clauses would trip detekt's `ThrowsCount` (max 2) if each threw directly, so the
     * actual `throw` lives in [conflict] instead.
     */
    private fun claimOrConflict(ref: WorkRef, operatorId: String) {
        try {
            taskService.assign(ref.sourceId, operatorId, tenantContext.clientId)
        } catch (_: TaskException.InvalidTransition) {
            conflict(ref, "already taken")
        } catch (_: TaskException.TransportPaused) {
            conflict(ref, "is paused")
        } catch (_: TaskException.NotFound) {
            conflict(ref, "not found")
        }
    }

    private fun findClaimedOrConflict(ref: WorkRef): WorkItem {
        val order = repository.findByIdAndClient(ref.sourceId, tenantContext.clientId)
            ?: conflict(ref, "not found")
        return order.toWorkItem(WorkState.CLAIMED)
    }

    private fun conflict(ref: WorkRef, reason: String): Nothing =
        throw WorkClaimConflictException("Transport order ${ref.sourceId} $reason")

    override fun release(ref: WorkRef, operatorId: String, asManager: Boolean) {
        taskService.release(ref.sourceId, operatorId, tenantContext.clientId, asManager)
    }

    private fun TransportOrder.toWorkItem(state: WorkState) = WorkItem(
        ref = WorkRef(WorkType.valueOf(transportType.name), id!!),
        workType = WorkType.valueOf(transportType.name),
        priority = prio,
        state = state,
        claimedBy = operatorId,
        zone = null,
        primaryLocation = sourceLocationName,
        destination = destinationLocationName ?: suggestedLocationName,
        summary = "${transportType.name} $unitLoadLabel from $sourceLocationName",
        createdAt = created,
        primaryLocationId = sourceLocationId,
    )
}

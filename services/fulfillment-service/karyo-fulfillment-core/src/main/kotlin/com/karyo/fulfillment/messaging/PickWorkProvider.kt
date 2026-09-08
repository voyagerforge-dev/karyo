package com.karyo.fulfillment.messaging

import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.security.TenantContext
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.work.spi.WorkProvider
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PickWorkProvider(
    private val repository: PickOrderRepository,
    private val service: PickOrderService,
    private val tenantContext: TenantContext,
) : WorkProvider {

    override fun workTypes(): Set<WorkType> = setOf(WorkType.PICK)

    override fun listOpen(filter: WorkFilter): List<WorkItem> {
        val types = filter.types
        if (types != null && !types.contains(WorkType.PICK)) return emptyList()
        return repository.findClaimable(tenantContext.clientId).map { it.toWorkItem(WorkState.OPEN) }
    }

    override fun listClaimedBy(operatorId: String): List<WorkItem> =
        repository.findClaimedBy(tenantContext.clientId, operatorId).map { it.toWorkItem(WorkState.CLAIMED) }

    override fun claim(ref: WorkRef, operatorId: String): WorkItem =
        service.claim(ref.sourceId, operatorId).toWorkItem(WorkState.CLAIMED)

    override fun release(ref: WorkRef, operatorId: String, asManager: Boolean) = service.release(ref.sourceId, operatorId, asManager)

    private fun PickOrder.toWorkItem(state: WorkState) = WorkItem(
        ref = WorkRef(WorkType.PICK, id!!),
        workType = WorkType.PICK,
        priority = prio,
        state = state,
        claimedBy = operatorId,
        zone = null,
        primaryLocation = null,
        destination = null,
        // Row 20: an EXTINGUISH order has no backing DeliveryOrder -- deliveryOrderNumber is
        // null, so the label falls back to the pick order's own number alone. Sprint B: a BULK
        // batch order is labeled ahead of the plain wave-batch case (both have
        // deliveryOrderNumber == null and a non-null waveId, but bulk carries its own aggregated
        // pick semantics and deserves its own label rather than the generic "Batch pick order").
        summary = when {
            bulk -> "Bulk pick order $pickOrderNumber (wave $waveId)"
            deliveryOrderNumber != null -> "Pick order $pickOrderNumber (delivery $deliveryOrderNumber)"
            waveId != null -> "Batch pick order $pickOrderNumber (wave $waveId)"
            else -> "Pick order $pickOrderNumber (stock clearance)"
        },
        createdAt = created,
        // No location concept on PickOrder yet (matches primaryLocation = null above) —
        // excluded whenever ?workingAreaId= narrows the pool (Task 8, see WorkItem KDoc).
        primaryLocationId = null,
    )
}

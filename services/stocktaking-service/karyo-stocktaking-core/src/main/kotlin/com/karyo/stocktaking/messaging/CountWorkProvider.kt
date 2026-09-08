package com.karyo.stocktaking.messaging

import com.karyo.layout.spi.LocationLockPort
import com.karyo.security.TenantContext
import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.service.StocktakingService
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkItem
import com.karyo.work.dto.WorkRef
import com.karyo.work.spi.WorkProvider
import com.karyo.work.vo.WorkState
import com.karyo.work.vo.WorkType
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CountWorkProvider(
    private val repository: CountOrderRepository,
    private val service: StocktakingService,
    private val tenantContext: TenantContext,
    private val locationLockPort: LocationLockPort,
) : WorkProvider {

    private companion object { const val DEFAULT_COUNT_PRIORITY = 50 }

    override fun workTypes(): Set<WorkType> = setOf(WorkType.COUNT)

    override fun listOpen(filter: WorkFilter): List<WorkItem> {
        val types = filter.types
        if (types != null && !types.contains(WorkType.COUNT)) return emptyList()
        return toWorkItems(repository.findClaimable(tenantContext.clientId), WorkState.OPEN)
    }

    override fun listClaimedBy(operatorId: String): List<WorkItem> =
        toWorkItems(repository.findClaimedBy(tenantContext.clientId, operatorId), WorkState.CLAIMED)

    override fun claim(ref: WorkRef, operatorId: String): WorkItem {
        val order = service.claim(ref.sourceId, operatorId)
        val travelOrder = locationLockPort.orderIndexFor(listOf(order.locationId))[order.locationId]
        return order.toWorkItem(WorkState.CLAIMED, travelOrder)
    }

    override fun release(ref: WorkRef, operatorId: String, asManager: Boolean) = service.release(ref.sourceId, operatorId, asManager)

    /**
     * Batches [LocationLockPort.orderIndexFor] once per [orders] list (never per-order) to
     * populate [WorkItem.travelOrder] — the read side of St6's walking-order seam. Only
     * [claim] (a single order) skips the batching, since there is nothing to batch.
     */
    private fun toWorkItems(orders: List<CountOrder>, state: WorkState): List<WorkItem> {
        val travelOrders = locationLockPort.orderIndexFor(orders.map { it.locationId })
        return orders.map { it.toWorkItem(state, travelOrders[it.locationId]) }
    }

    private fun CountOrder.toWorkItem(state: WorkState, travelOrder: Int?) = WorkItem(
        ref = WorkRef(WorkType.COUNT, id!!),
        workType = WorkType.COUNT,
        priority = DEFAULT_COUNT_PRIORITY,
        state = state,
        claimedBy = operatorId,
        zone = null,
        primaryLocation = locationName,
        destination = null,
        summary = "Count $locationName (order $orderNumber)",
        createdAt = created,
        primaryLocationId = locationId,
        travelOrder = travelOrder,
    )
}

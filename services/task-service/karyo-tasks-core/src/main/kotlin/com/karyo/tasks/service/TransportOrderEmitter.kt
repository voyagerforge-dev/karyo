package com.karyo.tasks.service

import com.karyo.events.outbox.OutboxService
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.dto.TransportOrderResponse
import com.karyo.tasks.event.TransportOrderCompletedEvent
import com.karyo.tasks.event.TransportOrderStateChangedEvent
import com.karyo.tasks.exception.TaskException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import java.time.Instant

/**
 * Single owner of the transport-order state-change/completed-event/response plumbing:
 * [transition] (canAdvanceTo guard + CDI event + outbox row), [emitStateChange] (the shared
 * event-plus-outbox write that [transition] and every direct state assignment that bypasses its
 * forward-only guard funnels through), [fireCompleted] (the completion event, reading
 * [TransportOrder.confirmedAmount] off the entity -- callers set it before calling), and
 * [toResponse] (entity to DTO mapping). Extracted from [TaskService], [ChainContinuationService],
 * and [ConfirmVariantService], which each previously carried their own private copy of this
 * plumbing.
 */
@ApplicationScoped
class TransportOrderEmitter(
    private val outboxService: OutboxService,
    private val stateChangedEvent: Event<TransportOrderStateChangedEvent>,
    private val completedEvent: Event<TransportOrderCompletedEvent>,
) {

    /**
     * Single chokepoint for transport-order state changes: enforces [OrderState.canAdvanceTo],
     * fires the synchronous CDI event, and writes the outbox row.
     */
    internal fun transition(order: TransportOrder, target: OrderState) {
        val current = OrderState.fromCode(order.state)
        if (!current.canAdvanceTo(target)) {
            throw TaskException.InvalidTransition(order.id ?: 0L, current.code, target.code)
        }
        order.state = target.code
        emitStateChange(order, current.code, target.code)
    }

    /**
     * Constructs the [TransportOrderStateChangedEvent], publishes it to the outbox, and fires
     * the synchronous CDI event. Used by [transition] itself, and directly by callers that
     * assign [TransportOrder.state] outside the forward-only guard (e.g. [TaskService.release],
     * [ChainContinuationService]'s successor minting).
     */
    internal fun emitStateChange(order: TransportOrder, from: Int, to: Int) {
        val event = TransportOrderStateChangedEvent(
            transportOrderId = order.id!!,
            orderNumber = order.orderNumber,
            oldState = from,
            newState = to,
            clientId = order.clientId,
            occurredAt = Instant.now(),
        )
        outboxService.publish("TransportOrder", order.id!!, "TransportOrderStateChanged", event, order.clientId)
        stateChangedEvent.fire(event)
    }

    /**
     * Fires the completion event and writes its outbox row. Reads [TransportOrder.confirmedAmount]
     * off the entity rather than taking it as a parameter -- callers set it before calling this.
     * [partial] defaults to `false` for the ordinary whole-UL path ([TaskService.complete]);
     * [ConfirmVariantService]'s merge/partial-confirm branches pass it explicitly.
     */
    internal fun fireCompleted(
        order: TransportOrder,
        destinationLocationId: Long?,
        destinationLocationName: String?,
        partial: Boolean = false,
    ) {
        val event = TransportOrderCompletedEvent(
            transportOrderId = order.id!!,
            orderNumber = order.orderNumber,
            transportType = order.transportType.name,
            unitLoadId = order.unitLoadId,
            unitLoadLabel = order.unitLoadLabel,
            sourceLocationId = order.sourceLocationId,
            sourceLocationName = order.sourceLocationName,
            destinationLocationId = destinationLocationId!!,
            destinationLocationName = destinationLocationName!!,
            operatorId = order.operatorId,
            clientId = order.clientId,
            occurredAt = Instant.now(),
            itemDataId = order.itemDataId,
            amount = order.amount,
            confirmedAmount = order.confirmedAmount,
            partial = partial,
        )
        outboxService.publish("TransportOrder", order.id!!, "TransportOrderCompleted", event, order.clientId)
        completedEvent.fire(event)
    }

    /** Entity to DTO mapping, shared by every state-mutating operation across the three callers. */
    internal fun toResponse(order: TransportOrder): TransportOrderResponse =
        TransportOrderResponse(
            id = order.id!!,
            orderNumber = order.orderNumber,
            transportType = order.transportType.name,
            unitLoadId = order.unitLoadId,
            unitLoadLabel = order.unitLoadLabel,
            sourceLocationId = order.sourceLocationId,
            sourceLocationName = order.sourceLocationName,
            destinationLocationId = order.destinationLocationId,
            destinationLocationName = order.destinationLocationName,
            suggestedLocationId = order.suggestedLocationId,
            suggestedLocationName = order.suggestedLocationName,
            state = order.state,
            stateName = OrderState.fromCode(order.state).name,
            prio = order.prio,
            operatorId = order.operatorId,
            executorType = order.executorType,
            note = order.note,
            goodsReceiptLineId = order.goodsReceiptLineId,
            clientId = order.clientId,
            created = order.created.toString(),
            modified = order.modified.toString(),
            pausedAt = order.pausedAt?.toString(),
            started = order.started?.toString(),
            finished = order.finished?.toString(),
            successorId = order.successorId,
            externalNumber = order.externalNumber,
            externalId = order.externalId,
            itemDataId = order.itemDataId,
            itemDataNumber = order.itemDataNumber,
            lotNumber = order.lotNumber,
            amount = order.amount,
            confirmedAmount = order.confirmedAmount,
            sourceStockUnitId = order.sourceStockUnitId,
        )
}

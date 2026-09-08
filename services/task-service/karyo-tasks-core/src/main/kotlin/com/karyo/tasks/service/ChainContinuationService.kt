package com.karyo.tasks.service

import com.karyo.layout.spi.LocationFinder
import com.karyo.layout.spi.LocationLockPort
import com.karyo.orders.vo.OrderState
import com.karyo.sequence.SequenceNumberService
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.repository.TransportOrderRepository
import com.karyo.tasks.vo.TransportType
import jakarta.enterprise.context.ApplicationScoped

/**
 * PT15 (putaway-transport sprint, Task 2): multi-hop transport chains via transfer-staging
 * areas — Karyo's own design, not a myWMS concept.
 *
 * Split out of [TaskService] purely because that class was already at Detekt's
 * `TooManyFunctions` ceiling, [TaskService.complete] makes exactly ONE call into
 * this class ([maybeChain]) rather than growing a new function of its own.
 *
 * **Semantics:** [TaskService.complete] calls [maybeChain] AFTER the physical unit-load
 * move but BEFORE it releases the predecessor's own location reservation. If the location the
 * predecessor actually completed to ([destId]) belongs to a transfer-staging
 * [com.karyo.layout.domain.model.StorageArea] ([LocationLockPort.isTransferStaging]), AND the
 * predecessor was still carrying a different real final target
 * ([TransportOrder.suggestedLocationId], set before this call and untouched by the move
 * itself), then the completed-to location was a waypoint, not the destination: a TRANSFER
 * successor is minted to carry the unit load the rest of the way, from that waypoint to the
 * predecessor's original final target. Otherwise (an ordinary completion, or one that already
 * landed on the real final target) this is a no-op and [TaskService.complete] proceeds exactly
 * as before PT15.
 *
 * **Denorm-at-creation (final-gate fix):** a successor is minted via `TransportOrder().apply {}`
 * directly, not through [TaskService]'s own create* paths — so it originally skipped
 * [ConfirmVariantService.denormalizeAtCreation], leaving `itemDataId`/`itemDataNumber`/
 * `lotNumber`/`amount`/`sourceStockUnitId` null on every chained hop even though the predecessor's
 * stock is known at mint time. [maybeChain] now calls [ConfirmVariantService.denormalizeAtCreation]
 * on the successor before persisting it, same as every other creation path.
 *
 * **Reservation re-key:** the predecessor's soft reservation (if any — most predecessors
 * reserved [TransportOrder.suggestedLocationId] via [LocationFinder.findPutawayLocation] at
 * creation time) is released and an equivalent reservation is written for the successor at the
 * SAME location, so the chain's claim on that final target survives the hop instead of briefly
 * evaporating (which would let another concurrent putaway grab it). [TaskService.complete]'s own
 * subsequent `locationFinder.releaseReservation(order.id!!)` call still runs after this — it is
 * a harmless idempotent no-op for the predecessor id, whose reservation this method already
 * released.
 *
 * **Recursive — a real multi-hop chain, not one hop:** [maybeChain] runs on EVERY completion,
 * predecessor or successor alike ([TaskService.complete] doesn't distinguish). A successor is
 * itself a [TransportOrder] with its own [TransportOrder.suggestedLocationId] (the chain's real
 * final target, inherited unchanged from its own predecessor), so if IT is later completed onto
 * yet another transfer-staging location short of that target, this method fires again and mints
 * a third hop — and so on. The chain only terminates when a hop completes either AT the final
 * target (`finalTarget == destId`) or at any ordinary, non-staging location — either way
 * [maybeChain] returns early and [TaskService.complete] proceeds as normal. Each hop re-keys the
 * reservation onto itself (see below) and stamps its own predecessor's [TransportOrder.successorId],
 * so the chain is walkable forward, hop by hop, from the first order — see [TransferChainFlowTest]
 * for both the single-hop and chain-of-chains pins.
 *
 * **PT17 interaction:** [ConfirmVariantService.completePartialIfApplicable] returns `null` (no-op)
 * whenever [com.karyo.tasks.dto.CompleteTransportOrderRequest.amount] is `null` or `>=` the
 * source stock's amount, so [TaskService.complete] falls through to the SAME whole-UL path this
 * class hooks — a confirm supplying an amount equal to the full stock amount still CHAINS exactly
 * like an amount-less confirm; only a strictly-partial amount skips [maybeChain] entirely (see
 * [ConfirmVariantService.completePartialIfApplicable]'s own KDoc).
 */
@ApplicationScoped
class ChainContinuationService(
    private val repository: TransportOrderRepository,
    private val locationFinder: LocationFinder,
    private val locationLockPort: LocationLockPort,
    private val confirmVariantService: ConfirmVariantService,
    private val sequenceNumberService: SequenceNumberService,
    private val emitter: TransportOrderEmitter,
) {

    /**
     * See the class KDoc for the full decision + re-key semantics. [predecessor] must already
     * be a managed entity in the caller's transaction (as [TaskService.complete] passes it) —
     * stamping [TransportOrder.successorId] on it here relies on that.
     */
    fun maybeChain(predecessor: TransportOrder, destId: Long, destName: String) {
        val finalTarget = predecessor.suggestedLocationId ?: return
        if (finalTarget == destId) return
        if (!locationLockPort.isTransferStaging(destId)) return

        val successor = TransportOrder().apply {
            clientId = predecessor.clientId
            orderNumber = generateOrderNumber(predecessor.clientId)
            transportType = TransportType.TRANSFER
            unitLoadId = predecessor.unitLoadId
            unitLoadLabel = predecessor.unitLoadLabel
            sourceLocationId = destId
            sourceLocationName = destName
            suggestedLocationId = finalTarget
            suggestedLocationName = predecessor.suggestedLocationName
            prio = predecessor.prio
        }
        confirmVariantService.denormalizeAtCreation(successor, successor.unitLoadId)
        repository.persist(successor)
        emitCreatedThenReleased(successor)

        locationFinder.releaseReservation(predecessor.id!!)
        locationFinder.reserve(finalTarget, successor.id!!)
        predecessor.successorId = successor.id
    }

    /** CREATED then RELEASED, same two-step shape as [TaskService.createManualMove]/
     *  [TaskService.createReplenishment] — a successor is queued work immediately, not parked. */
    private fun emitCreatedThenReleased(order: TransportOrder) {
        order.state = OrderState.CREATED.code
        emitter.emitStateChange(order, OrderState.UNDEFINED.code, OrderState.CREATED.code)
        order.state = OrderState.RELEASED.code
        emitter.emitStateChange(order, OrderState.CREATED.code, OrderState.RELEASED.code)
    }

    /**
     * Mirrors [TaskService.generateOrderNumber]'s shape, but its own `transport.transferNumber`
     * sequence name — a TRANSFER successor is a distinct number space from PUTAWAY/MOVE/REPLENISH,
     * not another prefix sharing their counter. `transport_orders.order_number` is VARCHAR(100).
     */
    private fun generateOrderNumber(clientId: Long): String =
        sequenceNumberService.next("transport.transferNumber", "TR", clientId, MAX_NUMBER_LENGTH) { candidate ->
            repository.findByOrderNumber(candidate, clientId) == null
        }

    companion object {
        private const val MAX_NUMBER_LENGTH = 100
    }
}

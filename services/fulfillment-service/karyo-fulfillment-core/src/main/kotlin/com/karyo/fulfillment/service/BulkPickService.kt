package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.api.v1.dto.BulkConfirmRequest
import com.karyo.fulfillment.api.v1.dto.BulkConfirmResponse
import com.karyo.fulfillment.api.v1.dto.BulkLineResponse
import com.karyo.fulfillment.api.v1.dto.BulkSliceOutcome
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockUnitLookup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal

/**
 * Bulk Allocation Sprint B: bulk pick mechanics -- the aggregated (one line per source stock
 * unit) presentation of a bulk PickOrder and the fan-out confirm that turns "I picked N of SKU X
 * from this stock unit" into per-slice confirms in allocation order (see [BulkFanOut]). Kept as
 * its own class rather than adding to [PickOrderService] because that class already sits at
 * detekt's `TooManyFunctions` ceiling (`allowedFunctionsPerClass: 25`, `config/detekt/detekt.yml`)
 * -- one more function there trips it deterministically. Explicit `clientId` on every entry point
 * (no ambient `TenantContext` in this class); the per-slice mutation delegates to
 * [PickOrderService.confirmPick] and to this class's own [shortPickToZero], reaching
 * [PickOrderService]'s shared completion machinery through its widened `internal` helpers rather
 * than duplicating it. Rows :2030/:2061: [shortPickToZero]'s reservation release now runs through
 * the explicit-`clientId` [StockPicker.releaseUnpickedReservation] overload, so it no longer
 * depends on the ambient tenant at all; [PickOrderService.confirmPick] itself stays ambient
 * (REST-only, and its route is now fronted by [requireNotOnBulkOrder] so a bulk order's slices
 * can only be confirmed through this class's fan-out).
 */
@ApplicationScoped
class BulkPickService(
    private val pickRepository: PickRepository,
    private val pickOrderRepository: PickOrderRepository,
    private val stockPicker: StockPicker,
    private val pickOrderService: PickOrderService,
    private val stockUnitLookup: StockUnitLookup,
    private val outboxService: OutboxService,
) {

    /**
     * Bulk Allocation Sprint B: a bulk-confirm slice that received NOTHING. Not reachable from
     * REST (the public confirm keeps its `> 0` guard); only [BulkPickService] callers ([bulkConfirm])
     * call it, for the tail slices of a short bulk pick. Mirrors the batch
     * branch of [PickOrderService]'s `recoverShortfall`: the whole planned reservation is
     * released, no follow-up, no substitution (release-only ruling), the pick is terminal PICKED
     * with `pickedAmount = 0`, and the same completion hooks [PickOrderService.confirmPick] runs
     * so line-scoped member-order completion and the wave activity event stay correct.
     */
    @Transactional
    internal fun shortPickToZero(pickId: Long, clientId: Long): Pick {
        val pick = findConfirmablePick(pickId, clientId)
        val pickOrder = pickOrderRepository.findByIdAndClient(pick.pickOrderId, clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pick.pickOrderId)
        require(pickOrder.bulk) { "shortPickToZero is bulk-only (pick order ${pickOrder.id})" }

        pickOrderService.captureActuals(pick)
        stockPicker.releaseUnpickedReservation(pick.sourceStockUnitId, pick.plannedAmount, clientId)
        pick.pickedAmount = BigDecimal.ZERO
        pick.state = PickState.PICKED.code

        pickOrderService.finishPickOrderIfDone(pickOrder, clientId)
        pickOrderService.advanceWaveMemberOrderIfDone(pick, pickOrder, clientId)
        pickOrderService.fireWaveActivityEventIfLinked(pickOrder, clientId)
        return pick
    }

    /**
     * Aggregated (one line per source stock unit) view of [pickOrderId]'s still-open picks --
     * the projection a bulk-pick operator confirms against. `@Transactional` (read-only in
     * effect) so a caller re-reading right after [bulkConfirm] on the same request/test sees the
     * committed state rather than a stale first-level-cache snapshot from before the mutation.
     *
     * Row :2047 (defect-burndown-6, A7): a source stock unit missing from
     * [StockUnitLookup.findByIds] is REFUSED with a 404, never rendered. The lookup's own contract
     * is that absence means unknown, deleted, or foreign to [clientId] -- there is no honest
     * location or unit-load label to show for it, and the empty strings this used to emit would
     * walk an operator to a bin that does not exist. Only reachable when an open pick's source row
     * has been deleted or reassigned out from under it (corrupt state, not an ordinary flow).
     */
    @Transactional
    fun bulkLines(pickOrderId: Long, clientId: Long): List<BulkLineResponse> {
        val order = bulkOrder(pickOrderId, clientId)
        val picks = pickRepository.findByPickOrderId(order.id!!)
        val open = picks.filter { it.state < PickState.PICKED.code }
        if (open.isEmpty()) return emptyList()
        val stock = stockUnitLookup.findByIds(open.map { it.sourceStockUnitId }.toSet(), clientId).associateBy { it.id }
        return open.groupBy { it.sourceStockUnitId }.map { (stockUnitId, slices) ->
            val su = stock[stockUnitId] ?: throw FulfillmentException.NotFound("StockUnit", stockUnitId)
            toLine(stockUnitId, slices, picks, su)
        }
    }

    /**
     * Fan out [request.pickedAmount] across [pickOrderId]'s open picks on [request.sourceStockUnitId]
     * in allocation order ([BulkFanOut]): earlier slices are filled first, a later slice may be
     * short or receive nothing. `@Transactional`; a failure on any one slice's [PickOrderService.confirmPick]
     * or [shortPickToZero] is left to propagate uncaught, rolling back every slice already applied
     * in this call (all-or-nothing per bulk line, what the operator expects from one CONFIRM press).
     */
    @Transactional
    fun bulkConfirm(pickOrderId: Long, request: BulkConfirmRequest, clientId: Long): BulkConfirmResponse {
        val order = bulkOrder(pickOrderId, clientId)
        val open = pickRepository.findByPickOrderId(order.id!!)
            .filter { it.state < PickState.PICKED.code && it.sourceStockUnitId == request.sourceStockUnitId }
        if (open.isEmpty()) {
            throw FulfillmentException.InvalidPickConfirmation(
                "stock unit ${request.sourceStockUnitId} has no open slices on pick order $pickOrderId",
            )
        }
        val total = open.sumOf { it.plannedAmount }
        if (request.pickedAmount.signum() <= 0 || request.pickedAmount > total) {
            throw FulfillmentException.InvalidPickConfirmation(
                "pickedAmount must be in (0, $total] for stock unit ${request.sourceStockUnitId}",
            )
        }
        val outcomes = confirmSlices(open, request, clientId)
        // "Filled" = the slice received something (a full share, or a partial share still ahead
        // of the tail); "short" = the slice received nothing, the shortPickToZero(0) branch --
        // not "picked == planned". A slice that received a nonzero PARTIAL share (the one tail
        // slice a short confirm lands on) still counts as filled, same as a full one.
        val response = BulkConfirmResponse(
            pickOrderId = order.id!!,
            sourceStockUnitId = request.sourceStockUnitId,
            pickedAmount = request.pickedAmount,
            filledSlices = outcomes.count { it.picked.signum() > 0 },
            shortSlices = outcomes.count { it.picked.signum() == 0 },
            slices = outcomes,
        )
        outboxService.publish("PickOrder", order.id!!, "pick.bulk-confirmed", response, clientId)
        return response
    }

    /**
     * Row :2051 (defect-burndown-6, A5): the per-slice confirm route
     * (`POST /api/v1/picks/{id}/confirm`) is refused on a BULK pick order, whose slices are only
     * ever confirmed through [bulkConfirm]'s fan-out. Confirming one slice directly would leave
     * the bulk line advertising a planned total the fan-out can no longer honor, and the operator
     * has no way to see which slice was taken out from under them. The internal fan-out is
     * unaffected: it reaches [PickOrderService.confirmPick] directly, not through the route.
     *
     * An absent pick is a no-op, so [PickOrderService.confirmPick]'s own 404 stays the single
     * authority on pick existence rather than this guard racing it with a different answer.
     */
    fun requireNotOnBulkOrder(pickId: Long, clientId: Long) {
        val pick = pickRepository.findByIdAndClient(pickId, clientId) ?: return
        val order = pickOrderRepository.findByIdAndClient(pick.pickOrderId, clientId) ?: return
        if (order.bulk) {
            throw FulfillmentException.ValidationFailed(
                "pick $pickId belongs to bulk pick order ${order.id}; " +
                    "use POST /pick-orders/${order.id}/bulk-confirm",
            )
        }
    }

    /**
     * Applies [BulkFanOut.allocate]'s shares one slice at a time -- a positive share confirms
     * through [PickOrderService.confirmPick], a zero share through this class's own
     * [shortPickToZero]. Extracted out of [bulkConfirm] to keep that function's own `ThrowsCount`
     * at its two explicit validation throws (the allocator's own `require` calls and the two
     * delegate methods' throws belong to their own functions, not this one).
     */
    private fun confirmSlices(open: List<Pick>, request: BulkConfirmRequest, clientId: Long): List<BulkSliceOutcome> {
        val shares = BulkFanOut.allocate(open.map { BulkSlice(it.id!!, it.plannedAmount) }, request.pickedAmount)
        val byId = open.associateBy { it.id!! }
        return shares.map { share ->
            val planned = byId.getValue(share.pickId).plannedAmount
            val pick: Pick = if (share.amount.signum() > 0) {
                pickOrderService.confirmPick(share.pickId, share.amount, request.targetUnitLoadId)
            } else {
                shortPickToZero(share.pickId, clientId)
            }
            BulkSliceOutcome(pick.id!!, pick.deliveryOrderLineId, share.amount, planned)
        }
    }

    /**
     * `plannedTotal` sums only the still-OPEN slices ([slices], the `open` filter applied by the
     * [bulkLines] caller); `pickedTotal` sums ALL picks on this stock unit ([allPicks], unfiltered
     * by state). The two are therefore not drawn from the same population: `pickedTotal` is
     * nonzero only when some slices of this stock unit were already confirmed through the
     * per-pick route (`POST /picks/{id}/confirm`) while others on the same stock unit stayed
     * open and still show up in this bulk line.
     *
     * [su] is non-null by construction: [bulkLines] refuses the whole read when the lookup misses
     * a source stock unit (row :2047), so this function never has to decide what to render for an
     * absent one. `lotNumber` still falls back to the pick's own recorded lot, which is a genuine
     * second source for that one field rather than a fabricated placeholder.
     */
    private fun toLine(stockUnitId: Long, slices: List<Pick>, allPicks: List<Pick>, su: StockUnitResponse): BulkLineResponse {
        val first = slices.first()
        return BulkLineResponse(
            sourceStockUnitId = stockUnitId,
            locationName = su.locationName,
            unitLoadLabel = su.unitLoadLabel,
            itemDataId = first.itemDataId,
            itemDataNumber = first.itemDataNumber,
            lotNumber = su.lotNumber ?: first.lotNumber,
            plannedTotal = slices.sumOf { it.plannedAmount },
            pickedTotal = allPicks.filter { it.sourceStockUnitId == stockUnitId }.sumOf { it.pickedAmount },
            openSlices = slices.size,
        )
    }

    /**
     * The same not-found/already-terminal validation [PickOrderService.confirmPick] applies to a
     * pick id before touching it, extracted here so [shortPickToZero] stays within detekt's
     * `ThrowsCount` ceiling (its own lookup-and-guard throws would otherwise total three: this
     * validation contributes two, the pick order lookup one).
     */
    private fun findConfirmablePick(pickId: Long, clientId: Long): Pick {
        val pick = pickRepository.findByIdAndClient(pickId, clientId)
            ?: throw FulfillmentException.NotFound("Pick", pickId)
        if (pick.state >= PickState.PICKED.code) {
            throw FulfillmentException.InvalidPickConfirmation("Pick $pickId is already picked or canceled")
        }
        return pick
    }

    /** [pickOrderId] as a BULK-flagged PickOrder for [clientId], or a 404/409 refusal. */
    private fun bulkOrder(pickOrderId: Long, clientId: Long): PickOrder {
        val order = pickOrderRepository.findByIdAndClient(pickOrderId, clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pickOrderId)
        if (!order.bulk) throw FulfillmentException.ValidationFailed("pick order $pickOrderId is not a bulk pick order")
        return order
    }
}

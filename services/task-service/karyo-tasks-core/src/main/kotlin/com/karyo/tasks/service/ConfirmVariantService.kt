package com.karyo.tasks.service

import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.StockMover
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.api.vo.StockState
import com.karyo.layout.spi.LocationFinder
import com.karyo.orders.vo.OrderState
import com.karyo.tasks.domain.model.TransportOrder
import com.karyo.tasks.dto.CompleteTransportOrderRequest
import com.karyo.tasks.dto.TransportOrderResponse
import com.karyo.tasks.exception.TaskException
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

/**
 * Owns every "confirm a transport order via something other than a plain unit-load relocation"
 * variant: PT16 confirm-merge into an EXISTING unit load ([completeAsMerge]) and PT17 partial
 * confirms ([completePartialIfApplicable]), plus the PT17 moved-stock denorm-at-creation helper
 * ([denormalizeAtCreation]) that every one of [TaskService]'s create* functions calls.
 *
 * Split out of [TaskService] purely because that class sits at Detekt's `TooManyFunctions`
 * ceiling — [TaskService.complete] calls into this class rather than growing new private
 * functions of its own. [finishOrder] delegates the state-transition/event-firing/response
 * plumbing to the shared [TransportOrderEmitter] rather than carrying its own copy.
 */
@ApplicationScoped
class ConfirmVariantService(
    private val stockMover: StockMover,
    private val stockUnitLookup: StockUnitLookup,
    private val unitLoadLookup: UnitLoadLookup,
    private val locationFinder: LocationFinder,
    private val emitter: TransportOrderEmitter,
) {

    /**
     * PT17: stamps [TransportOrder.itemDataId]/[TransportOrder.itemDataNumber]/
     * [TransportOrder.lotNumber]/[TransportOrder.amount]/[TransportOrder.sourceStockUnitId] from
     * [unitLoadId]'s single live (non-DELETABLE) stock unit, when there is exactly one. A
     * multi-stock (or stockless) unit load leaves all five fields null — see
     * [TransportOrder.itemDataId]'s KDoc for why that's an honest gap, not a guess. Called by
     * every [TaskService] creation path (auto-putaway, manual move, replenishment) on the
     * in-memory order BEFORE it is persisted.
     *
     * Task 3 (defect-burndown-4, row 7): reads via [StockUnitLookup.findByUnitLoadId]'s
     * explicit-`clientId` overload, not the ambient-`TenantContext` one -- [createReplenishment]
     * is reachable from `ReplenishmentScheduler`'s `@Scheduled` multi-tenant scan graph, which
     * never primes `TenantContext`, so the ambient overload silently found nothing there.
     * `order.clientId` is safe to force-unwrap: every [TaskService] creation path stamps it
     * before calling this function.
     */
    fun denormalizeAtCreation(order: TransportOrder, unitLoadId: Long) {
        val theStock = singleLiveStockOrNull(unitLoadId, order.clientId!!) ?: return
        order.itemDataId = theStock.itemDataId
        order.itemDataNumber = theStock.itemDataNumber
        order.lotNumber = theStock.lotNumber
        order.amount = theStock.amount
        order.sourceStockUnitId = theStock.id
    }

    /**
     * PT16 (full) + PT17 (partial, [amount] non-null and strictly less than the source stock's
     * amount) confirm-merge branch of [TaskService.complete]: the operator points a STARTED task
     * at an EXISTING unit load ([destinationUnitLoadId]) instead of a location, folding the
     * order's own unit load's stock into it via [StockMover.transferToUnitLoad] rather than
     * relocating a unit load.
     *
     * Requires the order's own unit load to carry EXACTLY ONE live stock unit (409
     * [TaskException.MixedSourceLoad] otherwise) — the PT16 rule, unconditionally, whether or
     * not [amount] is supplied. [amount] null or `>=` the source stock's amount transfers the
     * WHOLE stock (PT16's original full-merge behavior, draining the source to DELETABLE when it
     * empties); a strictly smaller [amount] transfers only that much, leaving the remainder on
     * the source stock/unit load — [TransportOrder.confirmedAmount] and the completed event's
     * `partial` flag record which happened.
     *
     * [destinationUnitLoadId]'s location is read back from [UnitLoadLookup] AFTER
     * [StockMover.transferToUnitLoad] succeeds, so a cross-owner or unknown target already
     * surfaced its own 409/404 first -- this never fabricates a destination for a transfer that
     * didn't happen.
     *
     * **The transfer deliberately uses the AMBIENT [StockMover.transferToUnitLoad] overload, not
     * the explicit-`clientId` one** (defect-burndown-6 Task 2 ruling). This is a REST-only path,
     * so the real principal's scope is always primed -- and only that scope can tell the two
     * cross-owner refusals apart: an OWNER principal cannot see the foreign target at all and gets
     * a 404, while an OPS principal can see it and gets the 409 `CrossOwner` that names the actual
     * problem. A synthetic `ownerScoped` context cannot make that distinction; it would collapse
     * the OPS case into "unit load not found", a wrong diagnostic for an operator who just scanned
     * the load in front of them.
     *
     * Deliberately calls neither `UnitLoadMover` (the order's own unit load never moves, only
     * its stock content does) nor [ChainContinuationService.maybeChain]: a merge — full or
     * partial alike — is terminal by construction, not a transfer-staging waypoint a chain could
     * continue from.
     *
     * Final-gate guard: [destinationUnitLoadId] equal to [TransportOrder.unitLoadId] itself is a
     * 400 [TaskException.ValidationFailed] — merging a unit load's stock into ITSELF is nonsense
     * (the same live stock unit would be both source and target), never a legitimate confirm.
     */
    fun completeAsMerge(order: TransportOrder, destinationUnitLoadId: Long, amount: BigDecimal?): TransportOrderResponse {
        if (destinationUnitLoadId == order.unitLoadId) {
            throw TaskException.ValidationFailed("destinationUnitLoadId cannot be the order's own unit load")
        }
        val theStock = singleLiveStockOrThrow(order)
        val transferAmount = if (amount != null && amount < theStock.amount) amount else theStock.amount
        val partial = transferAmount < theStock.amount

        stockMover.transferToUnitLoad(theStock.id, destinationUnitLoadId, transferAmount, "TO ${order.orderNumber}")

        val targetUl = unitLoadLookup.findById(destinationUnitLoadId)
            ?: throw TaskException.InvalidReference("UnitLoad", "id=$destinationUnitLoadId")
        return finishOrder(order, targetUl.locationId, targetUl.locationName, transferAmount, partial)
    }

    /**
     * PT17: the [TaskService.complete] `amount != null` branch (when [CompleteTransportOrderRequest.destinationUnitLoadId]
     * is null — that shape goes through [completeAsMerge] instead). Returns `null` (no mutation
     * at all) when [CompleteTransportOrderRequest.amount] is `>=` the source stock's amount — the
     * caller then falls through to [TaskService.complete]'s EXISTING whole-UL-move path,
     * unchanged. A strictly smaller amount is a genuine PARTIAL: resolves a destination UNIT LOAD
     * (never a bare unit-load relocation, since only part of the source is moving) —
     * [CompleteTransportOrderRequest.destinationLocationId] (explicit override) or the order's
     * own suggestion, find-or-created via [StockMover.transferToLocation] — then transfers just
     * that amount there.
     *
     * The remainder stays on the source stock/unit load at the source location (asserted by
     * construction: neither is touched here beyond the [StockMover] call). [TransportOrder.unitLoadId]
     * never changes — the order tracked the SOURCE load; the moved goods' new home is in the
     * destination fields and the completed event, not a relocation of the order's own unit load.
     * The reservation is released in FULL (Karyo-native simplification: a partial confirm has no
     * myWMS precedent to be faithful to, and splitting the reservation itself is a future
     * refinement if partial-then-partial chains emerge, not attempted here).
     * [ChainContinuationService.maybeChain] is never called — a partial describes a quantity
     * split, not a whole-container relocation a chain could continue from.
     *
     * The single-live-stock requirement (409 [TaskException.MixedSourceLoad]) is enforced
     * whenever an amount is supplied AT ALL, even when the outcome turns out to be "treat as
     * full" — supplying an amount only makes sense against one unambiguous source stock.
     */
    fun completePartialIfApplicable(order: TransportOrder, request: CompleteTransportOrderRequest): TransportOrderResponse? {
        val amount = request.amount ?: return null
        val theStock = singleLiveStockOrThrow(order)
        if (amount >= theStock.amount) return null

        val destId = request.destinationLocationId ?: order.suggestedLocationId
            ?: throw TaskException.NoDestination(order.id!!)
        val destName = request.destinationLocationName
            ?: (if (request.destinationLocationId == null) order.suggestedLocationName else null)
            ?: destId.toString()

        stockMover.transferToLocation(theStock.id, destId, destName, amount, "TO ${order.orderNumber}")
        return finishOrder(order, destId, destName, amount, partial = true)
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun singleLiveStockOrThrow(order: TransportOrder): StockUnitResponse =
        singleLiveStockOrNull(order.unitLoadId, order.clientId!!) ?: throw TaskException.MixedSourceLoad(order.id!!)

    private fun singleLiveStockOrNull(unitLoadId: Long, clientId: Long): StockUnitResponse? =
        stockUnitLookup.findByUnitLoadId(unitLoadId, clientId)
            .filter { it.state != StockState.DELETABLE.code }
            .singleOrNull()

    /**
     * Task 3 (defect-burndown-4, rows 31 + 6): the LIVE (re-read at call time, not the
     * creation-time [TransportOrder.amount] snapshot) amount on [order]'s own unit load when it
     * carries EXACTLY ONE live stock unit; `null` otherwise (zero or multiple live stocks).
     * `TaskService.complete` uses this ONLY to compute the actually-relocated
     * [TransportOrder.confirmedAmount] on the whole-UL completion path, not to decide WHICH
     * completion path to take -- that call-site distinction matters: a live re-read is safe once
     * a whole-UL move has already been decided (it can only make the reported figure more
     * accurate), but using it to pick the request's `amount` in the first place would silently
     * discard an R13-computed top-up cap (see `TaskService.complete`'s KDoc, "R13 default-amount
     * rule", for why that call site deliberately does NOT use this helper). A multi-SKU unit load
     * (the only remaining `null` case here) leaves [TransportOrder.confirmedAmount] to fall back
     * to the snapshot -- the same multi-SKU case [singleLiveStockOrThrow] 409s
     * [TaskException.MixedSourceLoad] on, on the partial/merge paths.
     */
    internal fun liveSingleStockAmount(order: TransportOrder): BigDecimal? =
        singleLiveStockOrNull(order.unitLoadId, order.clientId!!)?.amount

    /**
     * Shared finish for both [completeAsMerge] and [completePartialIfApplicable]: destination
     * fields, [TransportOrder.confirmedAmount], reservation release, FINISHED transition,
     * completed event, response mapping -- the last three delegated to [TransportOrderEmitter].
     */
    private fun finishOrder(
        order: TransportOrder,
        destId: Long,
        destName: String,
        confirmedAmount: BigDecimal,
        partial: Boolean,
    ): TransportOrderResponse {
        order.destinationLocationId = destId
        order.destinationLocationName = destName
        order.confirmedAmount = confirmedAmount

        locationFinder.releaseReservation(order.id!!)
        emitter.transition(order, OrderState.FINISHED)
        order.finished = Instant.now()
        emitter.fireCompleted(order, destId, destName, partial)
        return emitter.toResponse(order)
    }
}

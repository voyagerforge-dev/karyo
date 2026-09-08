package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickOrderPicksAddedEvent
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.spi.PlannedPick
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.orders.spi.OrderForPicking
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Top-up picks (WORKLIST row 15) — split out of [PickOrderService] to keep that file's detekt
 * state untouched (fulfillment-core's one accepted finding lives there), same rationale as
 * [PickLifecycleService].
 *
 * Behavioral parity with myWMS's add-picks-to-order behavior (independent implementation). The
 * resulting Karyo behavior is specified in `docs/wiki/functional/outbound.md:282`.
 * [addPicksToOrder] is also the merge primitive Task 5's extinguish-order generator reuses to fold
 * new stock-clearance picks into an existing open EXT order.
 */
@ApplicationScoped
class PickTopUpService(
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val deliveryOrderLookup: DeliveryOrderLookup,
    private val stockUnitLookup: StockUnitLookup,
    private val stockReserver: StockReserver,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
) {

    /**
     * REST-facing top-up: re-reads [pickOrderId]'s backing DeliveryOrder live (via
     * [DeliveryOrderLookup.findForPicking] — the SAME read `PickOrderService.releaseToPicking`
     * used originally) and adds picks for whatever reservation slices are NOT already covered by
     * this PickOrder's existing non-CANCELED picks — i.e. reservations made or changed AFTER the
     * order was first released to picking.
     *
     * **"Covered" is computed honestly, not merely "a pick with this source exists":** slices and
     * existing picks are both grouped by (sourceStockUnitId, deliveryOrderLineId) and SUMMED, so a
     * slice is only as covered as the total planned amount already committed to it. This means a
     * CANCELED pick's amount does NOT count towards coverage (only non-CANCELED picks are summed)
     * — see [uncoveredSlices]'s KDoc for the reservation invariant this now enforces before any
     * recovered slice becomes a Pick.
     *
     * Throws [FulfillmentException.ValidationFailed] (409) in TWO distinguishable cases: nothing
     * to add at all (every slice already fully covered — checked BEFORE any reservation attempt),
     * or every RECOVERED slice failed to re-reserve (its stock was legitimately claimed elsewhere
     * in the interim — checked AFTER [reserveRecovered] runs, distinct from [addPicksToOrder]'s own
     * state-range 409).
     */
    @Transactional
    fun topUp(pickOrderId: Long): PickOrder {
        val (order, deliveryOrder) = loadOrderAndDeliveryOrder(pickOrderId)
        val toAdd = uncoveredSlices(order, deliveryOrder)
        if (toAdd.isEmpty()) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder $pickOrderId has nothing to add — every currently reserved slice is " +
                    "already covered by a non-CANCELED pick",
            )
        }
        val reservedSlices = reserveRecovered(order, toAdd)
        if (reservedSlices.isEmpty()) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder $pickOrderId has nothing to add — every recovered slice's stock was " +
                    "no longer available to reserve (claimed by another order in the interim)",
            )
        }
        addPicksToOrder(order, reservedSlices)
        return order
    }

    /**
     * Closes the reservation-backing gap: myWMS's `PickingOrderLineGenerator.generatePick` — the
     * ONE place myWMS ever constructs a pick — unconditionally reserves the source stock at
     * creation time. `addPicksToOrder` staying non-reserving is myWMS-faithful (its analogue,
     * `PickingOrderGenerator.addPicksToOrder`, only attaches already-reserved lines) — the
     * reservation belongs at THIS choke point instead, which is Karyo's analogue of `generatePick`.
     *
     * Only [UncoveredSlice.recovered] slices are (re-)reserved — a genuinely NEW slice was already
     * reserved by the normal pipeline (`OrderService.reserveLine`/`retryReservation`, which call
     * `StockReserver.reserve` at the moment the `OrderLineReservation` row was created), so
     * reserving it again here would double-reserve stock that's already correctly held.
     *
     * A recovered slice whose exact `sourceStockUnitId` can no longer cover its amount (e.g. a
     * DIFFERENT order legitimately reserved that stock after this slice's original pick was
     * canceled) is SKIPPED — not partially added, and critically, never silently succeeding by
     * letting a later `pickStock` consume that other order's live reservation instead.
     */
    private fun reserveRecovered(order: PickOrder, slices: List<UncoveredSlice>): List<PlannedPick> =
        slices.mapNotNull { slice ->
            val pp = slice.plannedPick
            val backed = !slice.recovered || stockReserver.reserveOnStockUnit(pp.sourceStockUnitId, pp.amount, order.pickOrderNumber)
            if (backed) pp else null
        }

    /** Extracted purely to keep [topUp]'s own throw count within detekt's ThrowsCount limit. */
    private fun loadOrderAndDeliveryOrder(pickOrderId: Long): Pair<PickOrder, OrderForPicking> {
        val order = pickOrderRepository.findByIdAndClient(pickOrderId, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pickOrderId)
        val deliveryOrderId = requireOrderBound(order)
        val deliveryOrder = deliveryOrderLookup.findForPicking(deliveryOrderId)
            ?: throw FulfillmentException.NotFound("DeliveryOrder", deliveryOrderId)
        return order to deliveryOrder
    }

    /**
     * Row 20 (V605): `topUp` re-reads live reservation slices off a DeliveryOrder, which an
     * EXTINGUISH order (null [PickOrder.deliveryOrderId]) simply doesn't have — refused with a
     * clear 409 rather than an NPE. Extracted to its own function (a single throw) so
     * [loadOrderAndDeliveryOrder] itself stays at its existing two-throw count.
     */
    private fun requireOrderBound(order: PickOrder): Long =
        order.deliveryOrderId ?: throw FulfillmentException.ValidationFailed(
            "PickOrder ${order.id} has no backing DeliveryOrder (EXTINGUISH order) — topUp only " +
                "applies to order-bound pick orders",
        )

    /**
     * myWMS `addPicksToOrder`: adds [planned] picks to [order], returning whichever planned picks
     * could NOT be added (myWMS contract — the caller decides what to do with a remainder, e.g.
     * Task 5's extinguish generator mints a new order for it).
     *
     * **Match rule (extensible per the sprint plan — order-bound OR the EXT marker, Task 5):**
     * for an order-bound order (`order.deliveryOrderId != null`), a planned pick is accepted only
     * if its `deliveryOrderLineId` genuinely belongs to that SAME DeliveryOrder (re-derived here
     * via [DeliveryOrderLookup], not trusted from the caller) — anything else is REJECTED and
     * returned, myWMS-style, never silently dropped or thrown. **For an EXTINGUISH order
     * (`order.deliveryOrderId == null` — the EXT marker itself, V605), every planned pick is
     * accepted unconditionally**: there is no DeliveryOrder line to validate against, and client
     * matching is already implicit — [order] and [planned] are both read/constructed under the
     * SAME [com.karyo.security.TenantContext] by [com.karyo.fulfillment.service.ExtinguishService],
     * so a cross-tenant mismatch can't reach this method at all (same reasoning as the order-bound
     * branch's clientId note).
     *
     * **Order state IS validated, but as a Karyo tightening, not a per-pick rejection:** myWMS
     * silently keeps an out-of-range order as-is and still (findings depending) adds the picks
     * anyway; Karyo instead REFUSES THE WHOLE CALL (throws, not a partial-reject list) unless
     * `order.state` is in `[RELEASED, PICKED)`. Accepted picks are always created at RELEASED —
     * `order.state` itself is deliberately NOT recalculated here (myWMS-faithful: a top-up never
     * un-does or advances the order's own progress).
     *
     * **Row B13:** the order-bound branch's line-validation lookup takes [order]'s own `clientId`
     * explicitly rather than the ambient [com.karyo.security.TenantContext] -- this call sits right
     * above [sourceAmountsFor]'s own explicit-`clientId` fix and gates whether that method is ever
     * reached at all for an order-bound top-up; leaving it ambient would silently reject every
     * planned pick (not merely misclassify them) whenever a future caller's thread doesn't prime
     * `TenantContext` to match [order]'s real owner.
     */
    @Transactional
    fun addPicksToOrder(order: PickOrder, planned: List<PlannedPick>): List<PlannedPick> {
        if (planned.isEmpty()) return emptyList()
        if (order.state < PickState.RELEASED.code || order.state >= PickState.PICKED.code) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder ${order.id} cannot accept new picks (state=${order.state} is outside [RELEASED,PICKED))",
            )
        }
        val boundDeliveryOrderId = order.deliveryOrderId
        val (accepted, rejected) = if (boundDeliveryOrderId == null) {
            planned to emptyList()
        } else {
            val validLineIds = deliveryOrderLookup.findForPicking(boundDeliveryOrderId, order.clientId)
                ?.lines?.map { it.lineId }?.toSet().orEmpty()
            planned.partition { it.deliveryOrderLineId != null && it.deliveryOrderLineId in validLineIds }
        }
        if (accepted.isNotEmpty()) {
            persistTopUp(order, accepted)
        }
        return rejected
    }

    /**
     * Persists [accepted] as RELEASED picks, mirroring `releaseToPicking`'s pick-construction
     * block. Row 20: for an EXTINGUISH order (`order.deliveryOrderId == null`) every merged pick
     * is stamped `pickingType = EXTINGUISH` unconditionally — the COMPLETE/PICK full-vs-partial
     * classification is meaningless for a stock-clearance pick (it always takes the FULL
     * available amount by construction, see [com.karyo.fulfillment.service.ExtinguishService]),
     * so the source-amount lookup [sourceAmountsFor] performs is skipped entirely for that case.
     */
    private fun persistTopUp(order: PickOrder, accepted: List<PlannedPick>) {
        val isExtinguish = order.deliveryOrderId == null
        val sourceAmounts = if (isExtinguish) emptyMap() else sourceAmountsFor(accepted, order.clientId)
        accepted.forEach { pp ->
            val full = sourceAmounts[pp.sourceStockUnitId]?.compareTo(pp.amount) == 0
            val pick = Pick().apply {
                this.clientId = order.clientId
                this.pickOrderId = order.id!!
                this.deliveryOrderLineId = pp.deliveryOrderLineId
                this.itemDataId = pp.itemDataId
                this.itemDataNumber = pp.itemDataNumber
                this.sourceStockUnitId = pp.sourceStockUnitId
                this.plannedAmount = pp.amount
                this.state = PickState.RELEASED.code
                this.lotNumber = pp.lotNumber
                this.pickingType = when {
                    isExtinguish -> PickingType.EXTINGUISH.name
                    full -> PickingType.COMPLETE.name
                    else -> PickingType.PICK.name
                }
            }
            pickRepository.persist(pick)
        }
        outboxService.publish(
            "PickOrder", order.id!!, "PickOrderPicksAdded",
            PickOrderPicksAddedEvent(
                order.id!!, order.pickOrderNumber, order.deliveryOrderId, order.clientId, accepted.size, Instant.now(),
            ),
            order.clientId,
        )
    }

    /**
     * Reservation slices from [deliveryOrder] whose (sourceStockUnitId, deliveryOrderLineId)
     * total isn't already matched by this [order]'s existing non-CANCELED picks, tagged with
     * whether each is [UncoveredSlice.recovered] (see below).
     *
     * **Invariant this method upholds: every pick `topUp` goes on to create is reservation-backed
     * at creation** — mirroring myWMS's `PickingOrderLineGenerator.generatePick`, the single choke
     * point where myWMS always bumps `reservedAmount` before a pick exists. `addPicksToOrder`
     * itself deliberately stays non-reserving (myWMS-faithful — its analogue only attaches
     * already-reserved lines); [reserveRecovered] is Karyo's equivalent choke point, called from
     * [topUp] between this method and [addPicksToOrder].
     *
     * **A per-line-canceled pick's slice IS treated as uncovered again — this is intentional, not
     * a bug.** `PickLifecycleService.cancelPick` releases the STOCK-side reservation but never
     * touches the ORDER-side `OrderLineReservation` bookkeeping row (that row is only removed by
     * `OrderService.cancel`, the whole-order cancel) — so the live reservation slice this method
     * reads from [DeliveryOrderLookup] is unchanged by a per-line pick cancel. Excluding CANCELED
     * picks from the "have" side means top-up re-materializes that exact slice — marked
     * [UncoveredSlice.recovered] = true whenever a CANCELED pick ever existed for this exact
     * (sourceStockUnitId, deliveryOrderLineId) key, so [reserveRecovered] knows it must re-reserve
     * that specific stock unit before a replacement Pick is created, closing the gap where an
     * unbacked pick could otherwise silently consume a DIFFERENT order's live reservation at
     * confirm time (`DefaultStockPicker.pickStock` moves stock by physical `availableAmount`, not
     * by requiring a pre-existing reservation of its own).
     *
     * A key with NO prior CANCELED pick (`recovered = false`) is assumed already reservation-backed
     * by the normal pipeline (`OrderService.reserveLine`/`retryReservation`, which call
     * `StockReserver.reserve` at the moment its `OrderLineReservation` row was created) — NOT
     * re-reserved, to avoid double-reserving stock that's already correctly held.
     */
    private fun uncoveredSlices(order: PickOrder, deliveryOrder: OrderForPicking): List<UncoveredSlice> {
        val existingByKey = pickRepository.findByPickOrderId(order.id!!)
            .groupBy { it.sourceStockUnitId to it.deliveryOrderLineId }
        val have = existingByKey.mapValues { (_, picks) ->
            picks.filter { it.state != PickState.CANCELED.code }.sumOf { it.plannedAmount }
        }
        val everCanceled = existingByKey.mapValues { (_, picks) -> picks.any { it.state == PickState.CANCELED.code } }

        return flattenReservations(deliveryOrder)
            .groupBy { it.sourceStockUnitId to it.deliveryOrderLineId }
            .mapNotNull { (key, slices) ->
                val wanted = slices.sumOf { it.amount }
                val delta = wanted.subtract(have[key] ?: BigDecimal.ZERO)
                if (delta.signum() > 0) {
                    UncoveredSlice(slices.first().copy(amount = delta), recovered = everCanceled[key] == true)
                } else {
                    null
                }
            }
    }

    /**
     * One uncovered reservation slice, ready to become a Pick. [recovered] = true means a
     * CANCELED pick previously existed for this exact (sourceStockUnitId, deliveryOrderLineId)
     * key — see [uncoveredSlices]'s KDoc for why that specifically is what needs re-reserving.
     */
    private data class UncoveredSlice(val plannedPick: PlannedPick, val recovered: Boolean)

    /** Flatten the order's per-line reservation slices into one PlannedPick each (mirrors PickOrderService). */
    private fun flattenReservations(order: OrderForPicking): List<PlannedPick> =
        order.lines.flatMap { line ->
            line.reservations.map { slice ->
                PlannedPick(
                    deliveryOrderLineId = line.lineId,
                    itemDataId = line.itemDataId,
                    itemDataNumber = line.itemDataNumber,
                    lotNumber = line.lotNumber,
                    sourceStockUnitId = slice.stockUnitId,
                    amount = slice.amount,
                )
            }
        }

    /**
     * Source stock amounts keyed by stock-unit id (for COMPLETE/PICK labeling) -- mirrors
     * [PickOrderService.sourceAmountsFor] (row B13): takes [clientId] explicitly rather than
     * resolving it from the ambient [TenantContext], same rationale as that sibling method's
     * KDoc -- a caller whose thread never primed `TenantContext` (this service has no scheduler
     * today, but [addPicksToOrder]'s line-validation call right above this one was fixed the same
     * way for the identical reason) would otherwise see client 0's empty stock and misclassify
     * every fully-covering slice as PICK instead of COMPLETE.
     */
    private fun sourceAmountsFor(picks: List<PlannedPick>, clientId: Long): Map<Long, BigDecimal> =
        picks.map { it.itemDataId }.distinct()
            .flatMap { stockUnitLookup.findByItemDataId(it, clientId) }
            .associate { it.id to it.amount }
}

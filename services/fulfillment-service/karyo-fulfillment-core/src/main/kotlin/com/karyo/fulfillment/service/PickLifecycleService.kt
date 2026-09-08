package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickOrderCanceledEvent
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * PickOrder/Pick cancel lifecycle (WORKLIST row 14), split out of [PickOrderService] to keep that
 * file's detekt state untouched (fulfillment-core's one accepted finding lives there).
 *
 * Behavioral parity with myWMS's order-level force-finish and its per-line pick cancellation
 * (independent implementation). The resulting Karyo behavior is specified in
 * `docs/wiki/functional/outbound.md:134-137` and `:282`.
 * Deliberately NOT carried over: myWMS's `resetPickingOrder` split/regenerate machinery (Karyo's existing
 * `PickOrderService.release` already covers "interrupt and let anyone resume") and any `PAUSED`
 * state (myWMS never pauses a PickingOrder — sprint adjudication 1).
 *
 * **Order-level "FINISHED" is spelled PICKED here.** myWMS's force-finish lands the order on a
 * distinct FINISHED(700) code. Karyo's PickState has no such code — the existing terminal
 * completion state for a fully-worked PickOrder is already PICKED(600) (see
 * [PickOrderService.confirmPick]'s completion check). Force-finish's "some picks were kept" branch
 * reuses that identical terminal code; only the "nothing was ever picked" branch gets the distinct
 * CANCELED(800) code. This keeps a single terminal-completion representation for PickOrder rather
 * than inventing a parallel one.
 *
 * **Delivery-order reconciliation is deliberately NOT invoked here.** Unlike `confirmPick`, which
 * calls `OrderProgressionPort.markPicked` on order-level cancel completion, this service does not
 * drive [PickOrderRepository]'s `deliveryOrderId` order through any transition: the backing
 * DeliveryOrder's line rollups (`PickRollupLookup.sumPickedByLineIds`) already read PICKED picks
 * ONLY, so a canceled pick simply never contributes to `pickedAmount` — the derived rollup
 * self-corrects with no explicit call needed. The DeliveryOrder itself stays at STARTED (< PICKED)
 * after either cancel outcome (Karyo's terminal-completion state for a PickOrder canceled early
 * with picks kept is PICKED, but that call only advances the PICK ORDER, not the parent
 * DeliveryOrder — the DeliveryOrder still needs its own confirm/cancel path), which is exactly the
 * state range the existing DeliveryOrder-level cancel endpoint already gates on.
 */
@ApplicationScoped
class PickLifecycleService(
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val stockPicker: StockPicker,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
) {

    /**
     * Force-finish cancel (adjudication 2): every open (pre-PICKED) pick is CANCELED with its
     * outstanding reservation released via [StockPicker.releaseUnpickedReservation] — the exact
     * path `PickOrderService.handleShortfall` already uses, so the release publishes the same
     * `AmountChanged`/RELEASE outbox row a short-pick release does. The order lands on
     * CANCELED if nothing was ever PICKED, else on PICKED (see class KDoc for why PICKED, not a
     * distinct FINISHED code). Refused (409) once the order is already at or past PICKED.
     *
     * Authorization mirrors [PickOrderService.release]'s asManager pattern: an unclaimed RELEASED
     * order (operatorId null) is cancelable by any fulfillment-write principal; a STARTED order
     * claimed by someone else needs [asManager].
     *
     * **Adjudicated ruling on the PICKED-reuse hazard (task-1-review finding #2):** a force-finish
     * that keeps at least one genuinely PICKED pick lands the order on PICKED and is therefore
     * `openPacking`-eligible ([PackingService.openPacking] gates purely on
     * `pickOrder.state == PickState.PICKED.code`). This is INTENTIONAL, myWMS-faithful behavior —
     * "you pack what was picked"; the short signal lives on the DeliveryOrder's derived line rollup
     * (`pickedAmount < ordered`), not on the PickOrder state machine. What made this safe to leave
     * alone is [keptPickedCount] being counted
     * correctly (see below): an order with ZERO genuinely-PICKED picks — whether never touched, or
     * every pick canceled per-line first — always lands on CANCELED, which `openPacking`'s gate
     * already refuses (`state != PICKED`). So the "empty/zero-picked shipment silently opens
     * packing" hazard the review raised cannot occur; only the "partial-but-nonempty" case can,
     * and that case is the accepted, correct force-finish behavior described above.
     *
     * **Refined 2026-07-31 (defect-burndown-2 final gate).** The ruling above still holds for a
     * force-finish reached through fulfillment's own REST cancel: PICKED-and-packable is correct.
     * What changed is the delivery-order cancel cascade, which force-finishes here while the
     * DeliveryOrder itself goes CANCELED — one call now produces CANCELED order + PICKED PickOrder.
     * That pair IS a gap, and it is closed on the packing side by
     * [PackingService.openPacking]'s canceled-order guard (keyed on the DeliveryOrder's state, not
     * the PickOrder's), leaving the PICKED-reuse semantics here untouched.
     */
    @Transactional
    fun cancelOrder(pickOrderId: Long, username: String, asManager: Boolean): PickOrder {
        val order = pickOrderRepository.findByIdAndClient(pickOrderId, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pickOrderId)
        guardCancelable(order, username, asManager)
        return forceFinish(order)
    }

    /**
     * The force-finish itself, shared by [cancelOrder] (REST, guarded) and
     * [DefaultPickCancelPort] (the orders-module cancel cascade, which supersedes the claim
     * guards by design — a delivery-order cancel carries order-write authority over its own work).
     *
     * Publishes under the ORDER's own `clientId` (entity-owner attribution) rather than the
     * ambient [TenantContext] one. Identical in every currently-reachable REST case — [cancelOrder]
     * loads the order matching on exactly that clientId — and REQUIRED for the cascade, whose
     * caller may run under an OPS/system principal (ambient clientId 0) while the work belongs to
     * a goods owner.
     *
     * Rows :2030/:2061 extended that same entity-owner attribution to the STOCK side: the
     * reservation release in [releaseAndCancel] now runs through the explicit-`clientId`
     * [StockPicker.releaseUnpickedReservation] overload under `pick.clientId`. Before that, the
     * publish was owner-scoped but the release was ambient, so a cascade arriving with an
     * unprimed request scope threw `InventoryException.NotFound` and stranded the reservation.
     */
    internal fun forceFinish(order: PickOrder): PickOrder {
        val picks = pickRepository.findByPickOrderId(order.id!!)
        val openPicks = picks.filter { it.state < PickState.PICKED.code }
        openPicks.forEach { releaseAndCancel(it) }
        // Count picks GENUINELY PICKED, not "not currently open" — a pick already CANCELED via a
        // prior per-line cancelPick is `>= PICKED.code` too (so it's correctly excluded from
        // openPicks, avoiding a double-release) but must NOT count as "kept" here, or an order
        // where every pick was per-line-canceled would be mislabeled PICKED instead of CANCELED.
        val keptPickedCount = picks.count { it.state == PickState.PICKED.code }

        order.state = if (keptPickedCount == 0) PickState.CANCELED.code else PickState.PICKED.code
        order.finished = Instant.now()
        order.operatorId = null

        outboxService.publish(
            "PickOrder", order.id!!, "PickOrderCanceled",
            PickOrderCanceledEvent(
                order.id!!, order.pickOrderNumber, order.deliveryOrderId, order.clientId,
                openPicks.size, keptPickedCount, Instant.now(),
            ),
            order.clientId,
        )
        return order
    }

    /**
     * myWMS `cancelPick`: no-op refused (409) once the pick has reached PICKED (or is already
     * CANCELED — both are `>= PICKED.code`); otherwise releases the pick's outstanding reservation
     * and marks it CANCELED. Deliberately does NOT recalc/advance the parent [PickOrder] — myWMS
     * doesn't either. [orderId] is the nested-path parent; a pick that exists but belongs to a
     * DIFFERENT order 404s exactly like an unknown pick (never leaks cross-order existence).
     *
     * A canceled sibling does not strand the order: [PickOrderService.confirmPick]'s completion
     * predicate treats CANCELED as terminal alongside PICKED, so confirming the remaining open
     * picks still completes the order around this one.
     *
     * **M6 fix (final review):** authorization mirrors [cancelOrder] exactly — a per-line cancel
     * is scoped by the PARENT PickOrder's claim (a Pick carries no operatorId of its own). An
     * unclaimed order (operatorId null) is per-line-cancelable by any fulfillment-write principal;
     * an order claimed by someone else needs [asManager]. Before this fix, `cancelPick` had NO
     * ownership check at all, unlike [cancelOrder] — an asymmetric hole that let any
     * fulfillment-write principal cancel individual picks of an order claimed by a different
     * operator.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun cancelPick(orderId: Long, pickId: Long, username: String, asManager: Boolean): Pick {
        val clientId = tenantContext.clientId
        val pick = pickRepository.findByIdAndClient(pickId, clientId)
            ?.takeIf { it.pickOrderId == orderId }
            ?: throw FulfillmentException.NotFound("Pick", pickId)
        if (pick.state >= PickState.PICKED.code) {
            throw FulfillmentException.ValidationFailed(
                "Pick $pickId cannot be canceled (state=${pick.state} is already at or past PICKED)",
            )
        }
        val order = pickOrderRepository.findByIdAndClient(orderId, clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", orderId)
        guardOwnership(order, username, asManager)
        releaseAndCancel(pick)
        return pick
    }

    /** Refused (409) once at/past PICKED, or claimed by a different operator without [asManager]. */
    private fun guardCancelable(order: PickOrder, username: String, asManager: Boolean) {
        if (order.state >= PickState.PICKED.code) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder ${order.id} cannot be canceled (state=${order.state} is already at or past PICKED)",
            )
        }
        guardOwnership(order, username, asManager)
    }

    /** Shared by [cancelOrder] and [cancelPick] (M6 fix). */
    private fun guardOwnership(order: PickOrder, username: String, asManager: Boolean) {
        if (order.operatorId != null && order.operatorId != username && !asManager) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder ${order.id} is claimed by a different operator",
            )
        }
    }

    /** Releases the still-outstanding (planned minus already-picked) reservation, then marks CANCELED. */
    private fun releaseAndCancel(pick: Pick) {
        val outstanding = pick.plannedAmount.subtract(pick.pickedAmount)
        if (outstanding.signum() > 0) {
            // Explicit clientId (rows :2030/:2061): [forceFinish] is also the NON-REST entry
            // (DefaultPickCancelPort, WavePickService.cancelOpenForWave), where the request
            // scope is active but UNPRIMED and the ambient clientId is 0 -- an ambient release
            // would resolve against the wrong tenant and strand the reservation.
            stockPicker.releaseUnpickedReservation(pick.sourceStockUnitId, outstanding, pick.clientId)
        }
        pick.state = PickState.CANCELED.code
    }
}

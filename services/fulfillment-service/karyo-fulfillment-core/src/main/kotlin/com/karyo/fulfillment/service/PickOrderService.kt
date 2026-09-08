package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickOrderAutoPackEvent
import com.karyo.fulfillment.domain.event.PickOrderCreatedEvent
import com.karyo.fulfillment.domain.event.PickOrderPickedEvent
import com.karyo.fulfillment.domain.event.PickOrderReleasedEvent
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.event.PickingOrderPrepareEvent
import com.karyo.fulfillment.event.PlannedPickRef
import com.karyo.fulfillment.event.WavePickActivityEvent
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.work.exception.WorkClaimConflictException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.spi.GroupingRequest
import com.karyo.fulfillment.spi.PlannedPick
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.spi.ReservationRequest
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.spi.StagingLocation
import com.karyo.layout.spi.StagingLocationLookup
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.orders.spi.OrderForPicking
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.orders.spi.OrderStrategyLookup
import com.karyo.orders.spi.PickingStrategyView
import com.karyo.orders.vo.OrderState
import com.karyo.orders.vo.ShortPickMode
import com.karyo.product.spi.SubstitutionLookup
import com.karyo.fulfillment.spi.PickDifferenceContext
import com.karyo.fulfillment.spi.ShortfallContext
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class PickOrderService(
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val shipmentRepository: ShipmentRepository,
    private val grouping: PickOrderGroupingResolver,
    private val deliveryOrderLookup: DeliveryOrderLookup,
    private val orderProgressionPort: OrderProgressionPort,
    private val stagingLocationLookup: StagingLocationLookup,
    private val stockPicker: StockPicker,
    private val stockUnitLookup: StockUnitLookup,
    private val orderStrategyLookup: OrderStrategyLookup,
    private val stockReserver: StockReserver,
    private val substitutionLookup: SubstitutionLookup,
    private val shortfallResolver: ShortfallStrategyResolver,
    private val pickDifferenceResolver: PickDifferenceStrategyResolver,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
    private val pickingOrderPrepareEvent: Event<PickingOrderPrepareEvent>,
    // Row 8 (createShippingOrder): fired synchronously on pick-order completion but observed
    // AFTER_SUCCESS (PackingService.onAutoPackEvent) -- see PickOrderAutoPackEvent's KDoc for why
    // a direct same-transaction call does not work.
    private val autoPackEvent: Event<PickOrderAutoPackEvent>,
    private val sequenceNumberService: SequenceNumberService,
    // Task 4 (wave bulk fulfillment): fired once per confirmPick call on a wave-linked PickOrder
    // (waveId != null). A plain field/CDI-observer dependency on WaveTerminalChecker only (NOT on
    // BatchPickPort/WavePickService) -- WavePickService itself depends on PickOrderService for
    // its internal-helper reuse, so injecting the reverse direction here would be a circular CDI
    // bean graph. WaveTerminalChecker is the deliberately extracted, dependency-free-of-both,
    // shared "is this wave's pick work all terminal" query both sides call.
    private val waveActivityEvent: Event<WavePickActivityEvent>,
    private val waveTerminalChecker: WaveTerminalChecker,
    // Row 17: default "Pick Bin" UnitLoadType (V105 seed, usages PICKING, aggregate_stocks=TRUE),
    // replacing the former PICK_BIN_TYPE_ID=2L constant. Non-empty default per the SRCFG00040
    // boot-trap rule (never default an injected config string/number to blank). Advisory/
    // unvalidated beyond the existing UnitLoadType-existence check UnitLoadService.create already
    // performs -- no new cross-module validation SPI (sprint adjudication 4).
    @ConfigProperty(name = "karyo.fulfillment.pick-bin-unit-load-type-id", defaultValue = "2")
    private val defaultPickBinTypeId: Long,
) {

    /**
     * Row 17: request-param override wins over [defaultPickBinTypeId] when the caller names a
     * specific pick-bin UnitLoadType id. Widened to `internal` (Task 4) so [WavePickService]'s
     * own batch pick container creation resolves the same default, never a second config read.
     */
    internal fun resolvePickBinTypeId(requested: Long?): Long = requested ?: defaultPickBinTypeId

    /**
     * Row 8 (`createTypeOrders`): one delivery order can now yield more than one PickOrder, so the
     * return type widened from a single [PickOrder] to a list. Every existing caller (release
     * endpoint, tests, the copilot action executor) took the single return value and has been
     * updated. `POST /api/v1/pick-orders` now returns a JSON array in every case (release note,
     * outbound-completion register row 8) -- not sometimes-an-object-sometimes-an-array.
     *
     * The `sourceAmounts` full/COMPLETE-vs-PICK derivation is specified in
     * `docs/functional/picking.md#23-pick-order-generation` and is computed BEFORE grouping
     * runs, from the whole [plannedPicks] set, so the type is known per planned pick by the time
     * grouping (unchanged call) and the extension seam ([firePrepareEventAndFilter], unchanged,
     * fired once per grouping batch as before) have run. `createTypeOrders` then splits each
     * resolved grouping batch's unconsumed remainder by that already-known type -- OFF keeps one
     * PickOrder per batch exactly as before (even when the batch is type-mixed); a single-type
     * batch produces one PickOrder either way, flag or no flag.
     *
     * [clientId] defaults to the ambient context for the REST caller; the streaming engine passes
     * it explicitly (scheduler doctrine). Defaults to `null` (not a `tenantContext.clientId`
     * expression) and is resolved inside the method body -- Kotlin's synthetic `$default` bridge
     * evaluates a field-referencing default expression via a raw field read on whatever `this` is
     * at the call site, which is the ArC client proxy for every caller reaching this bean through
     * `@Inject`; that proxy's own copy of the `tenantContext` field is never populated (only the
     * real contextual instance's is), so a `tenantContext.clientId` default NPEs on every
     * pre-existing 2-arg call site. Resolving inside the body runs on the real instance instead.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun releaseToPicking(
        deliveryOrderId: Long,
        targetUnitLoadTypeId: Long? = null,
        clientId: Long? = null,
    ): List<PickOrder> {
        val clientId = clientId ?: tenantContext.clientId
        val order = deliveryOrderLookup.findForPicking(deliveryOrderId, clientId)
            ?: throw FulfillmentException.NotFound("DeliveryOrder", deliveryOrderId)
        // Fail fast with a domain-correct 409 if the order is already in (or past) picking, rather
        // than doing work and leaning on markStarted's forward-only guard to roll it all back.
        if (order.state >= OrderState.STARTED.code) {
            throw FulfillmentException.NotReleasable(deliveryOrderId, "already released to picking (state ${order.state})")
        }

        val plannedPicks = flattenReservations(order)
        if (plannedPicks.isEmpty()) {
            throw FulfillmentException.NotReleasable(deliveryOrderId, "no reserved stock to pick")
        }
        // Moved ahead of grouping (row 8) -- see the class KDoc above. sourceAmounts is keyed by
        // stockUnitId and independent of grouping/filtering order, so computing it from the full
        // pre-filter set is equivalent to (and a superset of) computing it from `unconsumed` per
        // batch, as the pre-row-8 code did.
        val sourceAmounts = sourceAmountsFor(plannedPicks, clientId)

        val staging = stagingLocationLookup.findPackStaging(clientId)
            ?: throw FulfillmentException.NotReleasable(deliveryOrderId, "no PACK_STAGING location configured")

        val knobs = orderStrategyLookup.findPickingStrategy(deliveryOrderId, clientId)
        // Row 8 destination resolution. Public behavioral contract:
        // docs/functional/picking.md#23-pick-order-generation. Karyo releases one delivery order
        // at a time, so the order's own destination wins, then the strategy default. A null
        // result stays null.
        val destinationLocationId = order.destinationLocationId ?: knobs?.defaultDestinationLocationId

        val result = grouping.resolve(
            GroupingRequest(order.orderId, order.orderNumber, clientId, plannedPicks),
        )

        val createdOrders = mutableListOf<PickOrder>()
        for (group in result.groups) {
            // PickingOrderPrepareEvent (row 16) -- fired AFTER grouping, BEFORE any persistence (no
            // container/PickOrder/Pick exists yet), once per grouping batch. See
            // firePrepareEventAndFilter's KDoc.
            val unconsumed = firePrepareEventAndFilter(order.orderId, group.picks)
            val subGroups = if (knobs?.createTypeOrders == true) {
                unconsumed.groupBy { pickingTypeOf(it, sourceAmounts) }.values.toList()
            } else {
                listOf(unconsumed)
            }
            subGroups.forEach { subGroup ->
                createdOrders += persistPickOrder(
                    order, clientId, staging, targetUnitLoadTypeId, destinationLocationId, subGroup, sourceAmounts,
                )
            }
        }

        orderProgressionPort.markStarted(order.orderId, clientId)
        return createdOrders
    }

    /**
     * The `full`/COMPLETE-vs-PICK derivation, shared by [releaseToPicking]'s split and its
     * per-Pick stamp. Widened to `internal` (Task 4, wave bulk fulfillment) so [WavePickService]
     * can reuse the exact same classification for its own COMPLETE/PICK routing split, never
     * duplicated.
     */
    internal fun pickingTypeOf(pp: PlannedPick, sourceAmounts: Map<Long, BigDecimal>): PickingType {
        val full = sourceAmounts[pp.sourceStockUnitId]?.compareTo(pp.amount) == 0
        return if (full) PickingType.COMPLETE else PickingType.PICK
    }

    /**
     * Persists one PickOrder (its own generated number, its own pick container) for [picks] plus
     * one Pick row each, and publishes its own PickOrderCreated outbox event. Extracted so
     * [releaseToPicking] can call it once per resulting group -- one for the whole release when
     * `createTypeOrders` is off (today's shape, unchanged), one per derived picking type when on.
     *
     * Widened to `internal` (Task 4, wave bulk fulfillment) so [WavePickService] can persist its
     * own per-order COMPLETE PickOrders through this exact same body (then stamp `waveId` on the
     * result) instead of duplicating the number-generation/container/Pick-row logic.
     */
    internal fun persistPickOrder(
        order: OrderForPicking,
        clientId: Long,
        staging: StagingLocation,
        targetUnitLoadTypeId: Long?,
        destinationLocationId: Long?,
        picks: List<PlannedPick>,
        sourceAmounts: Map<Long, BigDecimal>,
    ): PickOrder {
        // SC17: was a raw nanoTime tail with no conflict check (the four-site overflow/collision
        // landmine this rollout closes). pick_orders.pick_order_number is VARCHAR(80) (SC17 V606
        // widened it from 40 -- see that migration's KDoc) -- an
        // order number long enough to overflow now surfaces as SequenceException.TooLong (422)
        // instead of a DB 500. The SAME generated string is also used below as the pick
        // container's UnitLoad labelId (unit_loads.label_id is GLOBALLY unique, not scoped to
        // this pick order's own uniqueness). isUnique here checks only the pick-order-number
        // side: PickOrderRepository is the only reachable uniqueness source without adding a
        // new cross-module Gradle edge (fulfillment-core depends on karyo-inventory-API, not
        // -core, and UnitLoadLookup exposes no findByLabel). The label's own collision risk is
        // therefore unchanged from before this rollout -- millis+random plus the DB's UNIQUE
        // constraint on unit_loads.label_id remain the guard, same as every other generated
        // label site in the codebase that can't reach a same-module repository.
        val pickOrderNumber = sequenceNumberService.next(
            "pick.pickOrderNumber", "PO-${order.orderNumber}", clientId, MAX_NUMBER_LENGTH,
        ) { candidate -> pickOrderRepository.findByNumber(candidate, clientId) == null }
        val container = stockPicker.createPickContainer(
            clientId = clientId,
            unitLoadTypeId = resolvePickBinTypeId(targetUnitLoadTypeId),
            locationId = staging.id,
            locationName = staging.name,
            labelId = pickOrderNumber,
        )

        val pickOrder = PickOrder().apply {
            this.clientId = clientId
            this.pickOrderNumber = pickOrderNumber
            this.deliveryOrderId = order.orderId
            this.deliveryOrderNumber = order.orderNumber
            this.state = PickState.RELEASED.code
            this.targetUnitLoadId = container
            this.started = Instant.now()
            this.destinationLocationId = destinationLocationId
        }
        pickOrderRepository.persist(pickOrder)

        picks.forEach { pp ->
            val pick = Pick().apply {
                this.clientId = clientId
                this.pickOrderId = pickOrder.id!!
                this.deliveryOrderLineId = pp.deliveryOrderLineId
                this.itemDataId = pp.itemDataId
                this.itemDataNumber = pp.itemDataNumber
                this.sourceStockUnitId = pp.sourceStockUnitId
                this.plannedAmount = pp.amount
                this.state = PickState.RELEASED.code
                this.lotNumber = pp.lotNumber
                this.pickingType = pickingTypeOf(pp, sourceAmounts).name
            }
            pickRepository.persist(pick)
        }

        outboxService.publish(
            "PickOrder", pickOrder.id!!, "PickOrderCreated",
            PickOrderCreatedEvent(
                pickOrder.id!!, pickOrder.pickOrderNumber, order.orderId, clientId, picks.size, Instant.now(),
            ),
            clientId,
        )
        return pickOrder
    }

    /**
     * Confirm a pick of [pickedAmount] onto the target container. Accepts any amount in
     * (0, plannedAmount]: a short confirm picks what's there, then drives recovery per the order's
     * shortPickMode (follow-up re-selection + 1:1 substitution → follow-up Picks; uncovered remainder
     * → ShortfallStrategy). Moves the stock via StockPicker, marks the Pick PICKED, and completes the
     * PickOrder when all picks are in. [targetUnitLoadId] defaults to the PickOrder's container.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun confirmPick(pickId: Long, pickedAmount: BigDecimal, targetUnitLoadId: Long?): Pick {
        val clientId = tenantContext.clientId
        val pick = pickRepository.findByIdAndClient(pickId, clientId)
            ?: throw FulfillmentException.NotFound("Pick", pickId)
        // C1 fix (final review): terminal is PICKED *or* CANCELED. A per-line cancelPick or a
        // force-finish cancelOrder both mark a Pick CANCELED(800) and release its reservation --
        // if this guard only checked PICKED(600), a stray/retried/scripted confirm on that same
        // pick would sail through, moving stock via pickStock with NO backing reservation
        // (potentially consuming a DIFFERENT order's reservation that re-claimed the freed
        // stock), driving the completion predicate below to reassign a CANCELED order back to
        // PICKED, and firing markPicked/outbox for an order the business believes canceled.
        if (pick.state >= PickState.PICKED.code) {
            throw FulfillmentException.InvalidPickConfirmation("Pick $pickId is already picked or canceled")
        }
        if (pickedAmount.signum() <= 0 || pickedAmount > pick.plannedAmount) {
            throw FulfillmentException.InvalidPickConfirmation(
                "pickedAmount must be in (0, ${pick.plannedAmount}] for pick $pickId",
            )
        }
        val pickOrder = pickOrderRepository.findByIdAndClient(pick.pickOrderId, clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pick.pickOrderId)
        val targetUl = targetUnitLoadId ?: pickOrder.targetUnitLoadId
            ?: throw FulfillmentException.InvalidPickConfirmation("no target unit load for pick $pickId")

        // Row 18 (V604): capture what the source stock unit ACTUALLY carries BEFORE pickStock
        // mutates/consumes it -- the durable record of what was picked, independent of whatever
        // the source stock unit becomes afterwards (merged, decremented, later deleted).
        captureActuals(pick)

        // pickStock consumes the source's reservation for the picked quantity as part of the move
        // (it releases reservedAmount before transferring), so fulfillment doesn't touch reservations.
        val targetStockId = stockPicker.pickStock(pick.sourceStockUnitId, pickedAmount, targetUl)
        pick.pickedAmount = pickedAmount
        pick.targetStockUnitId = targetStockId
        pick.state = PickState.PICKED.code

        if (pickedAmount < pick.plannedAmount) {
            recoverShortfall(pick, pickOrder, pick.plannedAmount.subtract(pickedAmount), clientId)
        }

        finishPickOrderIfDone(pickOrder, clientId)
        advanceWaveMemberOrderIfDone(pick, pickOrder, clientId)
        fireWaveActivityEventIfLinked(pickOrder, clientId)

        return pick
    }

    /**
     * Task 4 review fix (CRITICAL-2, extracted to keep [confirmPick]'s own cyclomatic complexity
     * under the detekt ceiling): the recovery-machinery branch is keyed on the PICK ORDER being
     * order-bound (`pickOrder.deliveryOrderId != null`), not on the pick having a line id. A
     * cross-order BATCH pick order (wave PICK-slice pooling) carries `deliveryOrderId == null`
     * even though every one of its Picks DOES have a `deliveryOrderLineId` -- routing those
     * through [handleShortfall] hit its `pickOrder.deliveryOrderId ?: error(...)` invariant and
     * threw a 500 on every short confirm of a batch pick. v1 batch short-confirm is deliberately
     * release-only, same as EXTINGUISH: no follow-up re-selection or substitution for a batch
     * pick -- wave shortage recovery lives at allocation time (`generateForWave` / wave-core),
     * not floor recovery. The line is simply left short; the wave's own shortage handling
     * (already reported via `WavePickResult.droppedEachesLines` at generation time) is the
     * recovery surface, not a second one invented here.
     */
    private fun recoverShortfall(pick: Pick, pickOrder: PickOrder, shortfall: BigDecimal, clientId: Long) {
        if (pickOrder.deliveryOrderId != null) {
            handleShortfall(pick, pickOrder, shortfall, clientId)
        } else {
            // Row 20 (V605) / Task 4: an EXTINGUISH pick has no backing DeliveryOrder to
            // recover against -- myWMS's null-line branch "simply skips all delivery-order
            // integration" on confirm. A wave BATCH pick is release-only for the same reason
            // this branch already existed (see this method's KDoc above). Just free the
            // uncommitted portion of the reservation; no follow-up/substitution/shortfall-report
            // machinery applies to either case.
            stockPicker.releaseUnpickedReservation(pick.sourceStockUnitId, shortfall, clientId)
        }
    }

    /**
     * The PickOrder-level completion side effects, run once every pick of [pickOrder] has reached
     * a terminal state: stamps it PICKED/finished, advances the DISCRETE (non-wave) bound
     * delivery order when every sibling pick order is also done, and publishes the outbox
     * `PickOrderPicked` event. Extracted from [confirmPick] to keep that method's own cyclomatic
     * complexity under the detekt ceiling; merged with the former standalone "is it done" check
     * (only ever called from here) to keep this class's own function count under detekt's
     * TooManyFunctions ceiling too.
     *
     * A CANCELED pick ([PickLifecycleService.cancelPick]) must not strand the pick order forever:
     * it is terminal for completion purposes exactly like PICKED. [confirmPick] always just set
     * the just-confirmed pick itself to PICKED before calling this, so the all-of check below is
     * never vacuously true on an all-CANCELED pick order (order-level force-finish is
     * [PickLifecycleService.cancelOrder]'s job).
     *
     * Widened to `internal` (Bulk Allocation Sprint B) so [BulkPickService]'s `shortPickToZero`
     * can run the same PickOrder-level completion side effects for a zero-share bulk tail slice.
     */
    internal fun finishPickOrderIfDone(pickOrder: PickOrder, clientId: Long) {
        val allPicks = pickRepository.findByPickOrderId(pickOrder.id!!)
        if (!allPicks.all { it.state == PickState.PICKED.code || it.state == PickState.CANCELED.code }) return

        pickOrder.state = PickState.PICKED.code
        pickOrder.finished = Instant.now()
        // Row 20: an EXTINGUISH order has no DeliveryOrder to advance -- the completion is
        // purely a PickOrder-level fact for it (see class-level doctrine in PickLifecycleService).
        val boundDeliveryOrderId = pickOrder.deliveryOrderId
        // Row 8 review fix (Critical): the DELIVERY ORDER only reaches PICKED once EVERY
        // non-canceled SIBLING pick order (createTypeOrders can mint more than one) has ALSO
        // independently reached this same per-pick-order terminal state -- not just this one.
        // markPicked is deliberately strict, not wrapped in progressIfBehind: before
        // createTypeOrders, releaseToPicking's `.single()` meant a delivery order could only
        // ever own one PickOrder, so this call site could never replay it. Calling it
        // unconditionally on THIS pick order's own completion would have the second sibling to
        // finish call markPicked a second time -- canAdvanceTo refuses a same-state
        // transition, InvalidTransition propagates uncaught, and the WHOLE confirmation (incl.
        // the stock move that already happened) rolls back, forever (the first sibling's
        // PICKED write is durable, so a retry fails identically). allSiblingsComplete's own
        // query sees this pick order's `state = PICKED.code` write above via JPA's pre-query
        // auto-flush (same mechanism OrderService.cancel's KDoc documents for its own
        // terminal-slice query), so it is evaluated correctly even for THIS sibling.
        // Task 4 review fix (CRITICAL-1): this discrete-path check is scoped to `waveId ==
        // null` only. Before this fix, a wave-linked per-order COMPLETE PickOrder (waveId
        // set, deliveryOrderId ALSO set) would still trip this branch on its own completion,
        // via `allSiblingsComplete`'s PickOrderRepository.findAllByDeliveryOrderId query --
        // which can never see a cross-order BATCH PickOrder (deliveryOrderId always null) --
        // and wrongly declare the order fully picked while its wave-routed PICK-slice picks
        // in the batch order were still open. Wave order-completion is decided entirely by
        // [advanceWaveMemberOrderIfDone] instead; this branch keeps the ORIGINAL, unmodified
        // discrete (non-wave) behavior byte-identical.
        if (pickOrder.waveId == null && boundDeliveryOrderId != null &&
            allSiblingsComplete(boundDeliveryOrderId, clientId)
        ) {
            orderProgressionPort.markPicked(boundDeliveryOrderId, clientId)
            applyCompletionKnobs(boundDeliveryOrderId, clientId)
        }
        outboxService.publish(
            "PickOrder", pickOrder.id!!, "PickOrderPicked",
            PickOrderPickedEvent(
                pickOrder.id!!, pickOrder.pickOrderNumber, pickOrder.deliveryOrderId, clientId, Instant.now(),
            ),
            clientId,
        )
    }

    /**
     * Task 4 review fix (CRITICAL-1): wave order-completion is decided per CONFIRMED PICK, via
     * the pick's OWN owning order (resolved from its line, never from `pickOrder
     * .deliveryOrderId` -- a batch PickOrder carries none, and a PICK_ONLY member order may
     * never own a per-order PickOrder at all, so the old PickOrder.deliveryOrderId-keyed check
     * could never see either shape complete: a HYBRID order with a COMPLETE PickOrder AND open
     * batch picks would get marked PICKED early; a PICK_ONLY order would NEVER get marked
     * PICKED, stranding it at STARTED forever). Runs unconditionally on every wave pick confirm
     * -- independent of whether the (possibly multi-order) PickOrder container itself is fully
     * done -- because one batch PickOrder can hold lines from several member orders that
     * complete at different times. Extracted from [confirmPick] to keep that method's own
     * cyclomatic complexity under the detekt ceiling; the "does this order still have open
     * picks" check (across BOTH its own per-order (COMPLETE) PickOrder and any cross-order BATCH
     * PickOrder a wave routed its PICK slices into -- the line id is the only thread back to the
     * originating order for a batch-routed pick) is inlined here (only ever needed from this one
     * call site) to keep this class's own function count under detekt's TooManyFunctions ceiling
     * too. Mirrors [WavePickService.openPicksForOrders]'s single-order shape (same
     * [PickRepository.countOpenByLineIds] query) as an independent, dependency-free copy rather
     * than injecting `WavePickService`/`BatchPickPort` -- that class already injects THIS one for
     * its own internal-helper reuse, so the reverse edge would be a circular CDI bean graph (see
     * [WaveTerminalChecker]'s KDoc for the same reasoning applied to the wave-terminal check).
     *
     * Widened to `internal` (Bulk Allocation Sprint B) so [BulkPickService]'s `shortPickToZero`
     * can advance a wave member order the same way a zero-share bulk tail slice confirms.
     */
    internal fun advanceWaveMemberOrderIfDone(pick: Pick, pickOrder: PickOrder, clientId: Long) {
        if (pickOrder.waveId == null) return
        val lineId = pick.deliveryOrderLineId ?: return
        val memberOrderId = deliveryOrderLookup.orderIdForLine(lineId, clientId) ?: return
        val lineIds = deliveryOrderLookup.findForPicking(memberOrderId, clientId)?.lines?.map { it.lineId } ?: emptyList()
        if (pickRepository.countOpenByLineIds(lineIds, clientId) > 0) return

        orderProgressionPort.markPicked(memberOrderId, clientId)
        applyCompletionKnobs(memberOrderId, clientId)
    }

    /**
     * Task 4 (wave bulk fulfillment): fires on EVERY confirm of a wave-linked pick, not just on
     * pick-order completion -- a wave progress UI wants live activity, not only terminal events.
     * `waveAllTerminal` reflects the state AFTER [confirmPick]'s writes (the PICKED/CANCELED
     * stamp on the pick and, when applicable, the pick order -- both already flushed to the
     * persistence context by the time [WaveTerminalChecker]'s query runs, same auto-flush-
     * before-query reasoning as `allSiblingsComplete`'s KDoc). Extracted from [confirmPick] to
     * keep that method's own cyclomatic complexity under the detekt ceiling.
     *
     * Widened to `internal` (Bulk Allocation Sprint B) so [BulkPickService]'s `shortPickToZero`
     * fires the same wave activity event for a zero-share bulk tail slice.
     */
    internal fun fireWaveActivityEventIfLinked(pickOrder: PickOrder, clientId: Long) {
        val waveId = pickOrder.waveId ?: return
        waveActivityEvent.fire(
            WavePickActivityEvent(
                waveId, pickOrder.id!!, clientId, waveTerminalChecker.allTerminal(waveId, clientId),
            ),
        )
    }

    /**
     * Row 8 review fix (register row 8, Critical): true only when EVERY pick order for
     * [deliveryOrderId] has reached its own terminal state (PICKED or CANCELED, i.e.
     * `state >= PickState.PICKED.code` covers both numerically). Gates [confirmPick]'s call to
     * [orderProgressionPort]`.markPicked` -- see the call site's KDoc comment for the exact
     * failure this prevents. A delivery order with only one PickOrder (the pre-`createTypeOrders`
     * norm, and every case where the flag stays off) is trivially satisfied by that one order's
     * own completion, so this is a no-op widening for the common case.
     */
    private fun allSiblingsComplete(deliveryOrderId: Long, clientId: Long): Boolean =
        pickOrderRepository.findAllByDeliveryOrderId(deliveryOrderId, clientId)
            .all { it.state >= PickState.PICKED.code }

    /**
     * Row 18 (V604): stamps [pick]'s picked lot/best-before ACTUALS from its OWN source stock
     * unit (`pick.sourceStockUnitId` -- for a follow-up/substitution Pick this is already ITS
     * source, never the parent's), via the same batched [StockUnitLookup.findContentRefsByIds]
     * the packet content list already consumes. A missing content ref (deleted/unknown stock
     * unit) leaves both fields `null` -- honest gap, never fabricated -- which is also exactly
     * what happens for a Pick that is never confirmed (e.g. the uncovered remainder of a
     * short pick with no follow-up: no Pick exists for it, so nothing is ever stamped).
     *
     * Widened to `internal` (Bulk Allocation Sprint B) so [BulkPickService]'s `shortPickToZero`
     * stamps the same actuals for a zero-share bulk tail slice before releasing its reservation.
     */
    internal fun captureActuals(pick: Pick) {
        val ref = stockUnitLookup.findContentRefsByIds(setOf(pick.sourceStockUnitId))[pick.sourceStockUnitId]
        pick.pickedLotNumber = ref?.lotNumber
        pick.pickedBestBefore = ref?.bestBefore
    }

    /**
     * Row 8: applies the two pick-completion strategy flags, called right after [orderProgressionPort]
     * has already advanced the order to PICKED -- state parking ([sendToPacking]) never substitutes
     * for that mark, it only continues the order one step further ([OrderProgressionPort.markPacking]
     * follows the same [DefaultOrderProgressionPort]-documented `progressIfBehind` idempotency shape
     * as `markPacked`). `createShippingOrder`'s auto-open only FIRES [PickOrderAutoPackEvent] here --
     * see that event's KDoc for why the actual [PackingService.onAutoPackEvent] call must be
     * deferred to AFTER_SUCCESS rather than made directly from this (still-open) transaction.
     */
    private fun applyCompletionKnobs(deliveryOrderId: Long, clientId: Long) {
        val knobs = orderStrategyLookup.findPickingStrategy(deliveryOrderId)
        if (knobs?.sendToPacking == true) {
            orderProgressionPort.markPacking(deliveryOrderId, clientId)
        }
        if (knobs?.createShippingOrder == true) {
            autoPackEvent.fire(PickOrderAutoPackEvent(deliveryOrderId, clientId))
        }
    }

    /**
     * Short pick: free the leftover reservation, then cover the shortfall per the order's shortPickMode
     * (follow-up re-selection + 1:1 substitution → follow-up Picks), handing any uncovered remainder
     * to the ShortfallStrategy (v1.3 built-in: partial-ship — shortage report + accept).
     *
     * Exclusion is per-confirm: the [PickDifferenceStrategy] only excludes THIS pick's source from the
     * re-selection. A follow-up Pick that is itself later confirmed short excludes only its own source,
     * not the original short source earlier in the chain (bounded — it still terminates at the
     * ShortfallStrategy). Future WRITE_OFF/QUARANTINE strategies make the residual unselectable outright.
     */
    private fun handleShortfall(pick: Pick, pickOrder: PickOrder, shortfall: BigDecimal, clientId: Long) {
        // Guarded by the caller (confirmPick): handleShortfall only ever runs for an order-bound
        // pick, so both ids are non-null here. An EXTINGUISH pick takes the release-only branch
        // in confirmPick instead and never reaches this method.
        val deliveryOrderId = pickOrder.deliveryOrderId
            ?: error("handleShortfall requires an order-bound PickOrder; EXTINGUISH picks must not reach here")
        val lineId = pick.deliveryOrderLineId
            ?: error("handleShortfall requires an order-bound Pick; EXTINGUISH picks must not reach here")
        stockPicker.releaseUnpickedReservation(pick.sourceStockUnitId, shortfall, clientId)
        val knobs = orderStrategyLookup.findPickingStrategy(deliveryOrderId)
        // Decide what happens to the short source's residual (v1.3 LEAVE: leave it on the bin but
        // exclude it from the re-selection, so the follow-up sources from elsewhere).
        val exclude = pickDifferenceResolver.resolve(
            PickDifferenceContext(
                sourceStockUnitId = pick.sourceStockUnitId, itemDataId = pick.itemDataId,
                shortfall = shortfall, clientId = clientId,
                pickDifferenceStrategyName = knobs?.pickDifferenceStrategy ?: "LEAVE",
            ),
        ).excludeStockUnitIds
        val mode = parseMode(knobs?.shortPickMode)
        var remaining = shortfall

        if (mode == ShortPickMode.FOLLOW_UP || mode == ShortPickMode.FOLLOW_UP_THEN_SUBSTITUTE) {
            remaining = coverWithFollowUps(pick, pickOrder, pick.itemDataId, remaining, null, knobs, exclude)
        }
        if (remaining.signum() > 0 &&
            (mode == ShortPickMode.SUBSTITUTE_ONLY || mode == ShortPickMode.FOLLOW_UP_THEN_SUBSTITUTE)
        ) {
            for (sub in substitutionLookup.findSubstitutes(pick.itemDataId)) {
                if (remaining.signum() <= 0) break
                remaining = coverWithFollowUps(
                    pick, pickOrder, sub.substituteItemDataId, remaining, sub.substituteItemDataId, knobs, exclude,
                )
            }
        }
        if (remaining.signum() > 0) {
            shortfallResolver.resolve(
                ShortfallContext(
                    pickId = pick.id!!, deliveryOrderId = deliveryOrderId,
                    deliveryOrderLineId = lineId, itemDataId = pick.itemDataId,
                    remainder = remaining, clientId = clientId,
                    shortfallStrategyName = knobs?.shortfallStrategy ?: "PARTIAL_SHIP",
                ),
            )
        }
    }

    /** Reserve [amount] of [itemDataId] and turn each reserved slice into a follow-up Pick (RELEASED). */
    private fun coverWithFollowUps(
        parent: Pick, pickOrder: PickOrder, itemDataId: Long, amount: BigDecimal,
        substituteItemDataId: Long?, knobs: PickingStrategyView?, excludeStockUnitIds: List<Long>,
    ): BigDecimal {
        // Lot applies only to a same-item follow-up: re-select within the parent's lot and stamp it.
        // A substitute is a different item with its own lots, so we neither lot-restrict the reserve
        // nor stamp the parent's lot on the substitute Pick.
        val followUpLot = if (substituteItemDataId == null) parent.lotNumber else null
        val outcome = stockReserver.reserve(
            ReservationRequest(
                itemDataId = itemDataId, amount = amount,
                lotNumber = followUpLot,
                useLockedStock = knobs?.useLockedStock ?: false,
                preferComplete = knobs?.preferComplete ?: true,
                preferMatching = knobs?.preferMatching ?: false,
                completeHandling = knobs?.completeHandling ?: 0,
                enforceLot = knobs?.enforceLot ?: false,
                correlationId = pickOrder.pickOrderNumber,
                excludeStockUnitIds = excludeStockUnitIds,
            ),
        )
        outcome.reservations.forEach { slice ->
            val followUp = Pick().apply {
                this.clientId = parent.clientId
                this.pickOrderId = pickOrder.id!!
                this.deliveryOrderLineId = parent.deliveryOrderLineId
                this.itemDataId = itemDataId
                this.itemDataNumber = parent.itemDataNumber
                this.sourceStockUnitId = slice.stockUnitId
                this.plannedAmount = slice.amount
                this.state = PickState.RELEASED.code
                this.lotNumber = followUpLot
                this.pickingType = PickingType.PICK.name
                this.followUpForPickId = parent.id
                this.substitutedItemDataId = substituteItemDataId
            }
            pickRepository.persist(followUp)
        }
        return outcome.shortfall
    }

    private fun parseMode(raw: String?): ShortPickMode =
        raw?.let { runCatching { ShortPickMode.valueOf(it) }.getOrNull() } ?: ShortPickMode.DEFAULT

    fun getPickOrder(id: Long): PickOrder =
        pickOrderRepository.findByIdAndClient(id, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", id)

    fun picksOf(pickOrderId: Long): List<Pick> = pickRepository.findByPickOrderId(pickOrderId)

    fun listPickOrders(): List<PickOrder> = pickOrderRepository.findByClient(tenantContext.clientId)

    /**
     * Row :1470 (A8): derived, non-persisted "auto-open didn't happen yet" signal for the
     * pick-order DETAIL response -- see [com.karyo.fulfillment.api.v1.dto.PickOrderResponse
     * .autoOpenPending] for the full rationale and the list-path cost tradeoff. `true` iff ALL
     * of: (1) [po] is bound to a DeliveryOrder (an EXTINGUISH order has none -- nothing to
     * auto-open, always `false`); (2) [po] itself is PICKED -- the state [applyCompletionKnobs]
     * only ever runs from; (3) no non-canceled Shipment currently exists for the delivery order;
     * (4) that order's resolved strategy has `createShippingOrder` on -- the exact knob
     * [applyCompletionKnobs] reads to decide whether to fire [PickOrderAutoPackEvent] in the
     * first place. Condition 3 is what makes this self-healing: a manual "open packing" or a
     * later-succeeding auto-open both clear the pending state on the very next read, with no
     * stored flag to reconcile.
     *
     * **Query cost per branch (review fix, verified against [OrderStrategyLookup
     * .findPickingStrategy]'s implementation, [com.karyo.orders.service.DefaultOrderStrategyLookup]
     * + [com.karyo.orders.service.OrderStrategyService.resolve] -- NOT a claim, counted):**
     * - EXTINGUISH order, or a non-PICKED order: **0 queries** ([po]'s already-loaded fields only).
     * - PICKED order with an existing non-canceled Shipment (the COMMON case -- a
     *   `createShippingOrder` auto-open normally succeeds the first time; see
     *   [com.karyo.fulfillment.service.PackingService.openPacking]'s KDoc): **1 query**
     *   ([ShipmentRepository.existsNonCanceledByDeliveryOrderId]) -- returns `false` WITHOUT ever
     *   resolving the strategy. This is why the shipment check runs BEFORE the strategy lookup,
     *   not after: it is the cheap, usually-decisive branch.
     * - PICKED order with NO existing shipment (the rare, "did the auto-open actually fail"
     *   branch): 1 query (the same shipment check, this time returning empty) **+ 2 or 3 more**
     *   for [OrderStrategyLookup.findPickingStrategy] -- 1 for `DeliveryOrderRepository
     *   .findByIdAndClient`, +1 for `DefaultOrderStrategyResolver`'s `repository.findById` ONLY
     *   if the order carries an explicit `orderStrategyId` (skipped when it resolves to the
     *   seeded DEFAULT directly), +1 for `OrderStrategyService.resolve`'s closing
     *   `repository.findByName`. **Total: 3 or 4 queries.** This branch is NOT bounded to <= 2;
     *   it is deliberately rare (gated behind "no shipment exists yet") rather than free, and is
     *   documented here exactly instead of understated.
     */
    fun isAutoOpenPending(po: PickOrder): Boolean {
        val deliveryOrderId = po.deliveryOrderId ?: return false
        if (po.state != PickState.PICKED.code) return false
        // Shipment existence first (1 query, common case) -- only fall through to the up-to-3-query
        // strategy lookup when there is genuinely no shipment yet to explain away the "pending" read.
        if (shipmentRepository.existsNonCanceledByDeliveryOrderId(deliveryOrderId, po.clientId)) return false
        val knobs = orderStrategyLookup.findPickingStrategy(deliveryOrderId)
        return knobs?.createShippingOrder == true
    }

    /**
     * Claim a RELEASED, unclaimed PickOrder for [operatorId] (RELEASED→STARTED).
     * Used by the work-inbox to assign a pick to an operator.
     * Throws [WorkClaimConflictException] for BOTH the not-found case (delete-race) and the
     * wrong-state/already-claimed case so that [WorkDispatchService.getNext] skips the candidate
     * rather than aborting the dispatch loop with an unexpected exception.
     */
    @Transactional
    fun claim(pickOrderId: Long, operatorId: String): PickOrder {
        val clientId = tenantContext.clientId
        val order = pickOrderRepository.findByIdAndClient(pickOrderId, clientId)
            ?: throw WorkClaimConflictException("Pick order $pickOrderId not found")
        if (order.state != PickState.RELEASED.code || order.operatorId != null) {
            throw WorkClaimConflictException("Pick order $pickOrderId already taken (state=${order.state}, operatorId=${order.operatorId})")
        }
        order.operatorId = operatorId
        order.state = PickState.STARTED.code
        order.started = Instant.now()
        return order
    }

    /**
     * Release a STARTED PickOrder back to the unclaimed pool (STARTED→RELEASED).
     *
     * [operatorId] is the ACTOR performing the release, not necessarily the holder: with
     * [asManager] a manager may release work claimed by someone else. Both identities land in the
     * [PickOrderReleasedEvent] outbox row -- see that event's KDoc for why `managerOverride` is
     * derived from the two operators rather than from [asManager].
     *
     * @throws FulfillmentException.NotFound if the order doesn't exist for this tenant.
     * @throws FulfillmentException.ValidationFailed if the order is not STARTED or the operator doesn't match.
     */
    @Transactional
    fun release(pickOrderId: Long, operatorId: String, asManager: Boolean = false) {
        val clientId = tenantContext.clientId
        val order = pickOrderRepository.findByIdAndClient(pickOrderId, clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pickOrderId)
        if (order.state != PickState.STARTED.code) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder $pickOrderId is not in STARTED state (state=${order.state})",
            )
        }
        if (order.operatorId != operatorId && !asManager) {
            throw FulfillmentException.ValidationFailed(
                "PickOrder $pickOrderId is claimed by a different operator",
            )
        }
        val releasedFrom = order.operatorId
        order.operatorId = null
        order.state = PickState.RELEASED.code
        order.started = null
        outboxService.publish(
            "PickOrder", order.id!!, "PickOrderReleased",
            PickOrderReleasedEvent(
                pickOrderId = order.id!!,
                pickOrderNumber = order.pickOrderNumber,
                deliveryOrderId = order.deliveryOrderId,
                releasedFrom = releasedFrom,
                releasedBy = operatorId,
                managerOverride = releasedFrom != null && releasedFrom != operatorId,
                clientId = clientId,
                occurredAt = Instant.now(),
            ),
            clientId,
        )
    }

    /**
     * Flatten the order's per-line reservation slices into one PlannedPick each. Widened to
     * `internal` (Task 4) so [WavePickService] flattens a wave member order's reservations
     * exactly the same way `releaseToPicking` does.
     */
    internal fun flattenReservations(order: OrderForPicking): List<PlannedPick> =
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
     * Source stock amounts keyed by stock-unit id (for COMPLETE/PICK labeling), one lookup per
     * distinct item. Widened to `internal` (Task 4) for [WavePickService] reuse.
     *
     * Task 4 review fix (CRITICAL-1, wave bulk fulfillment): takes an explicit [clientId] and
     * calls [StockUnitLookup.findByItemDataId]'s two-arg overload -- the single-arg ambient
     * overload this used to call resolves against `TenantContext`, which `WaveScheduler`'s
     * `@Scheduled` multi-tenant auto-release loop never primes (same doctrine as
     * `ReplenishmentScheduler`/`CrossDockExpirySweep`). On that path the single-arg overload
     * silently returned an empty map for every real tenant, so [pickingTypeOf] could never see a
     * slice as `full` and classified EVERY slice PICK -- HYBRID auto-release then minted zero
     * per-order COMPLETE PickOrders, and COMPLETE_ONLY auto-release routed every fully-covered
     * slice into `droppedEachesLines`, whose default SKIP action releases the covering
     * reservation outright (silent reservation destruction). [releaseToPicking] already resolves
     * its own `clientId` from the ambient context before calling this, so the discrete (REST)
     * path is byte-identical to before.
     */
    internal fun sourceAmountsFor(picks: List<PlannedPick>, clientId: Long): Map<Long, BigDecimal> =
        picks.map { it.itemDataId }.distinct()
            .flatMap { stockUnitLookup.findByItemDataId(it, clientId) }
            .associate { it.id to it.amount }

    /**
     * Fires [PickingOrderPrepareEvent] synchronously (mirrors `ItemDataStateChangedEvent`'s
     * `Event<T>.fire()` idiom) with [candidatePicks] as the offered slices, then returns whichever
     * ones were NOT claimed via `consumedPickIndexes`. v1.3 ships no built-in observer — a pure
     * extension seam (myWMS itself has none either).
     *
     * Throws [FulfillmentException.AllPicksConsumedByExtension] when every index was consumed:
     * NO PickOrder is created for [deliveryOrderId] in that case (the caller learns an extension
     * took the whole batch rather than silently getting an empty order).
     */
    private fun firePrepareEventAndFilter(deliveryOrderId: Long, candidatePicks: List<PlannedPick>): List<PlannedPick> {
        val event = PickingOrderPrepareEvent(
            externalNumber = UUID.randomUUID().toString(),
            deliveryOrderId = deliveryOrderId,
            plannedPicks = candidatePicks.map { it.toRef() },
        )
        pickingOrderPrepareEvent.fire(event)
        val unconsumed = candidatePicks.filterIndexed { index, _ -> index !in event.consumedPickIndexes }
        if (unconsumed.isEmpty()) {
            throw FulfillmentException.AllPicksConsumedByExtension(deliveryOrderId)
        }
        return unconsumed
    }

    /**
     * Row 20: only ever called from [firePrepareEventAndFilter], which only ever runs inside
     * [releaseToPicking] over [flattenReservations]'s output — always an order-bound PlannedPick
     * (a real DeliveryOrder line id). `ExtinguishService` never fires
     * [PickingOrderPrepareEvent] (see its class KDoc: no analogous myWMS extension point for
     * `ExtinguishOrderGenerator`), so a null [PlannedPick.deliveryOrderLineId] can never reach
     * here — `!!` documents that invariant.
     */
    private fun PlannedPick.toRef() = PlannedPickRef(
        itemDataId = itemDataId,
        itemDataNumber = itemDataNumber,
        amount = amount,
        sourceStockUnitId = sourceStockUnitId,
        deliveryOrderLineId = deliveryOrderLineId!!,
        lotNumber = lotNumber,
    )

    companion object {
        /** pick_orders.pick_order_number is VARCHAR(80) (SC17 V606 — widened for the embedded order number + sequence tail). */
        private const val MAX_NUMBER_LENGTH = 80
    }
}

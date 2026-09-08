package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickOrderCreatedEvent
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.spi.BatchCart
import com.karyo.fulfillment.spi.BatchPickPort
import com.karyo.fulfillment.spi.DroppedLine
import com.karyo.fulfillment.spi.PickZoneLookup
import com.karyo.fulfillment.spi.PickedLineSlice
import com.karyo.fulfillment.spi.PlannedPick
import com.karyo.fulfillment.spi.WavePickRequest
import com.karyo.fulfillment.spi.WavePickResult
import com.karyo.fulfillment.spi.WavePickStats
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.layout.spi.StagingLocation
import com.karyo.layout.spi.StagingLocationLookup
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.orders.spi.OrderStrategyLookup
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Wave-shaped pick generation (Task 4, wave bulk fulfillment / Advanced Fulfillment pack). The
 * single [BatchPickPort] implementation registered by fulfillment-core -- consumed by
 * `karyo-wave-core` (which depends on `karyo-fulfillment-api`, never `-core`) so the wave module's
 * own services never touch `PickOrder`/`Pick` JPA entities directly.
 *
 * Deliberately reuses [PickOrderService]'s internal COMPLETE/PICK classification and per-order
 * persistence machinery ([PickOrderService.flattenReservations], `.sourceAmountsFor`,
 * `.pickingTypeOf`, `.persistPickOrder`, `.resolvePickBinTypeId` -- all widened `private` ->
 * `internal` for this reuse, see their KDocs) rather than duplicating it: a wave member order's
 * COMPLETE slices become an ordinary per-order PickOrder, indistinguishable from a discrete
 * release's, except for the `waveId` stamp applied after `persistPickOrder` returns.
 *
 * **Documented deviations of the cross-order BATCH path from the discrete path (reviewer-flagged,
 * intentional, not gaps):**
 * - [persistBatchPickOrders] never calls [PickOrderGroupingResolver]`.resolve` -- grouping
 *   strategies decide how ONE order's reserved work is split; a batch PickOrder is
 *   already the wave's OWN cross-order grouping decision (by [PickZoneLookup] zone), a different
 *   axis entirely, not a second layer on top of the per-order strategy chain.
 * - [persistBatchPickOrders] never fires [com.karyo.fulfillment.event.PickingOrderPrepareEvent]
 *   -- that hook's contract is "one released order's candidate picks, filterable by an outside
 *   extension before ANY PickOrder exists for that order" (see the event's own KDoc); a batch
 *   PickOrder's candidate picks span multiple orders at once, so firing it once per order would
 *   mean an extension partially claims a batch PickOrder that already has other orders' Picks
 *   persisted alongside it, and firing it once per batch (multi-order) would break the event's
 *   one-order contract outright. No built-in observer exists today either way (row 16's "pure
 *   hook, no built-in observer" stance carries over unchanged).
 * - A batch PickOrder always carries `destinationLocationId == null` (see [persistBatchPickOrders]),
 *   unlike a per-order COMPLETE PickOrder (which DOES resolve `order.destinationLocationId ?:
 *   strategy.defaultDestinationLocationId`, Task 4 review fix IMPORTANT-4): a batch PickOrder's
 *   Picks can belong to several member orders with DIFFERENT destinations, so there is no single
 *   destination to stamp at generation time -- resolving one belongs to whichever later stage
 *   (consolidation) picks a physical destination for the batch as a whole.
 */
@ApplicationScoped
class WavePickService(
    private val pickOrderService: PickOrderService,
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val deliveryOrderLookup: DeliveryOrderLookup,
    private val orderProgressionPort: OrderProgressionPort,
    private val stagingLocationLookup: StagingLocationLookup,
    private val orderStrategyLookup: OrderStrategyLookup,
    private val stockPicker: StockPicker,
    private val pickZoneLookup: PickZoneLookup,
    private val outboxService: OutboxService,
    private val sequenceNumberService: SequenceNumberService,
    private val waveTerminalChecker: WaveTerminalChecker,
    private val pickLifecycleService: PickLifecycleService,
) : BatchPickPort {

    /**
     * Per member order: flattens its reservation slices, splits them COMPLETE-vs-PICK exactly as
     * [PickOrderService.releaseToPicking] does, then routes per [WavePickRequest.wavePickMode]:
     * - `COMPLETE_ONLY`: COMPLETE slices become a per-order PickOrder; PICK slices are dropped
     *   (reported as [WavePickResult.droppedEachesLines], reservations left untouched -- the
     *   wave's own shortage handling decides what happens to them).
     * - `PICK_ONLY`: every slice (COMPLETE and PICK alike) goes to the cross-order batch path --
     *   no per-order PickOrder is created at all for this mode.
     * - `BULK`: same routing as PICK_ONLY plus `PickOrder.bulk = true` on every batch PickOrder
     *   this generates -- the sort station remains mandatory, aggregated presentation and
     *   bulk-confirm fan-out are built on top of the flag in later tasks.
     * - anything else (including `HYBRID`, the documented default): COMPLETE slices become a
     *   per-order PickOrder, PICK slices go to the batch path. An unrecognized mode string falls
     *   into this branch too -- HYBRID-shaped behavior is the safe default, never a thrown error,
     *   for a value this method does not itself validate (validation is the wave module's job).
     *
     * [OrderProgressionPort.markStarted] is called at most once per member order, after all of
     * that order's slices (COMPLETE and/or batch-routed) have been persisted -- never once per
     * PickOrder, since one order's PICK slices can land in a batch PickOrder shared with other
     * orders' slices.
     */
    @Transactional
    override fun generateForWave(request: WavePickRequest): WavePickResult {
        val clientId = request.clientId
        val staging = stagingLocationLookup.findPackStaging(clientId)
            ?: throw FulfillmentException.ValidationFailed(
                "no PACK_STAGING location configured for wave ${request.waveId}",
            )

        val acc = WaveGenerationAccumulator()
        request.deliveryOrderIds.forEach { orderId -> routeMemberOrder(orderId, request, clientId, staging, acc) }

        val batchPickOrderIds = if (acc.pickBucket.isEmpty()) {
            emptyList()
        } else {
            persistBatchPickOrders(
                request.waveId, clientId, staging, acc.pickBucket, bulk = request.wavePickMode == "BULK",
            )
        }

        acc.ordersToMarkStarted.forEach { orderProgressionPort.markStarted(it, clientId) }

        return WavePickResult(acc.pickOrderIds, batchPickOrderIds, acc.droppedEachesLines)
    }

    /** Mutable per-[generateForWave]-call accumulator, threaded through [routeMemberOrder] so that
     *  method (and the top-level [generateForWave]) each stay under detekt's cyclomatic-complexity
     *  ceiling instead of one large function owning every local. */
    private class WaveGenerationAccumulator {
        val pickOrderIds = mutableListOf<Long>()
        val droppedEachesLines = mutableListOf<DroppedLine>()
        val pickBucket = mutableListOf<PlannedPick>()
        val ordersToMarkStarted = mutableSetOf<Long>()
    }

    /**
     * One member order's worth of [generateForWave]'s routing decision (see that method's KDoc
     * for the per-mode rules). A not-found order OR one whose real owner doesn't match
     * [WavePickRequest.clientId] is a hard [FulfillmentException.ValidationFailed] -- Task 4
     * review fix (IMPORTANT-3). An order with no reservations left (`planned.isEmpty()`) is still
     * silently skipped -- that is a legitimate "nothing to generate for this order" state, not a
     * tenant-safety violation.
     *
     * **Correction (Task 9, wave bulk fulfillment sprint):** an earlier version of this KDoc
     * claimed [DeliveryOrderLookup.findForPicking] "still resolves against the AMBIENT
     * TenantContext ... an existing, untouched seam this task does not own", reasoning that a
     * drift between the ambient context and [request]'s explicit `clientId` was merely a defense-
     * in-depth concern. That premise was falsified by `WaveScheduler`'s `@Scheduled` multi-tenant
     * auto-release loop: it calls `WaveService.release` (this method's caller, transitively) from
     * a thread that never primes `TenantContext` at all, so the single-arg ambient overload found
     * NO order for EVERY wave member, not a drifted one. Fixed at the root: this now calls
     * [DeliveryOrderLookup.findForPicking]'s explicit-`clientId` overload with [request]'s own
     * `clientId`, so the read is correct whether the caller is a REST request or a scheduler tick
     * -- the `order.clientId != request.clientId` guard below still stands as the same
     * defense-in-depth check IMPORTANT-3 added, now simply never the ONLY thing standing between
     * a mismatch and silent misattribution.
     */
    private fun routeMemberOrder(
        orderId: Long,
        request: WavePickRequest,
        clientId: Long,
        staging: StagingLocation,
        acc: WaveGenerationAccumulator,
    ) {
        val order = deliveryOrderLookup.findForPicking(orderId, request.clientId)
        if (order == null || order.clientId != request.clientId) {
            throw FulfillmentException.ValidationFailed(
                "DeliveryOrder $orderId not found for client ${request.clientId} (wave ${request.waveId})",
            )
        }
        val planned = pickOrderService.flattenReservations(order)
        if (planned.isEmpty()) return
        val sourceAmounts = pickOrderService.sourceAmountsFor(planned, clientId)
        val (completeSlices, pickSlices) = planned.partition {
            pickOrderService.pickingTypeOf(it, sourceAmounts) == PickingType.COMPLETE
        }
        // Task 4 review fix (IMPORTANT-4): same fallback `releaseToPicking` applies (see its
        // KDoc) -- the order's own destination wins, falling back to the resolved strategy's
        // `defaultDestinationLocationId` rather than leaving a wave COMPLETE PickOrder with no
        // destination at all whenever the order itself doesn't carry one.
        //
        // Task 4 review fix (IMPORTANT-2, wave bulk fulfillment): explicit-`clientId` overload,
        // same reasoning as [pickOrderService.sourceAmountsFor] above -- the ambient single-arg
        // overload resolves via `DefaultOrderStrategyLookup`'s `TenantContext` read, which
        // `WaveScheduler`'s thread never primes, so it silently returned null on the scheduler
        // path and this fallback was inert every time.
        val knobs = orderStrategyLookup.findPickingStrategy(orderId, clientId)
        val destinationLocationId = order.destinationLocationId ?: knobs?.defaultDestinationLocationId

        fun persistOrderComplete() {
            val po = pickOrderService.persistPickOrder(
                order, clientId, staging, null, destinationLocationId, completeSlices, sourceAmounts,
            )
            po.waveId = request.waveId
            acc.pickOrderIds += po.id!!
            acc.ordersToMarkStarted += orderId
        }

        when (request.wavePickMode) {
            "COMPLETE_ONLY" -> {
                if (completeSlices.isNotEmpty()) persistOrderComplete()
                pickSlices.forEach {
                    acc.droppedEachesLines += DroppedLine(it.deliveryOrderLineId, it.itemDataId, it.amount)
                }
            }
            "PICK_ONLY", "BULK" -> {
                acc.pickBucket += planned
                acc.ordersToMarkStarted += orderId
            }
            else -> { // HYBRID (documented default) and any unrecognized mode string
                if (completeSlices.isNotEmpty()) persistOrderComplete()
                if (pickSlices.isNotEmpty()) {
                    acc.pickBucket += pickSlices
                    acc.ordersToMarkStarted += orderId
                }
            }
        }
    }

    /**
     * PICK-path slices resolved to a zone once via [PickZoneLookup] (null -> "UNZONED"), grouped,
     * and persisted as one cross-order batch PickOrder per zone: `deliveryOrderId`/
     * `deliveryOrderNumber` null, `waveId`/`batchZone` stamped, number from the dedicated
     * `wave.batchPickOrderNumber` sequence (prefix `WB-<waveId>`), container at pack staging same
     * as the discrete path. Each [Pick] keeps its own `deliveryOrderLineId` (its only remaining
     * thread back to the originating order/line once grouped into a shared PickOrder).
     */
    private fun persistBatchPickOrders(
        waveId: Long,
        clientId: Long,
        staging: StagingLocation,
        picks: List<PlannedPick>,
        bulk: Boolean,
    ): List<Long> {
        val zones = pickZoneLookup.zonesByStockUnitIds(picks.map { it.sourceStockUnitId }.toSet(), clientId)
        val batchSourceAmounts = pickOrderService.sourceAmountsFor(picks, clientId)
        val groups = picks.groupBy { zones[it.sourceStockUnitId] ?: UNZONED }

        return groups.map { (zoneKey, zonePicks) ->
            val pickOrderNumber = sequenceNumberService.next(
                "wave.batchPickOrderNumber", "WB-$waveId", clientId, MAX_NUMBER_LENGTH,
            ) { candidate -> pickOrderRepository.findByNumber(candidate, clientId) == null }

            val container = stockPicker.createPickContainer(
                clientId = clientId,
                unitLoadTypeId = pickOrderService.resolvePickBinTypeId(null),
                locationId = staging.id,
                locationName = staging.name,
                labelId = pickOrderNumber,
            )

            val batchOrder = PickOrder().apply {
                this.clientId = clientId
                this.pickOrderNumber = pickOrderNumber
                this.deliveryOrderId = null
                this.deliveryOrderNumber = null
                this.state = PickState.RELEASED.code
                this.targetUnitLoadId = container
                this.started = Instant.now()
                this.waveId = waveId
                this.batchZone = zoneKey
                this.bulk = bulk
            }
            pickOrderRepository.persist(batchOrder)

            zonePicks.forEach { pp ->
                val pick = Pick().apply {
                    this.clientId = clientId
                    this.pickOrderId = batchOrder.id!!
                    this.deliveryOrderLineId = pp.deliveryOrderLineId
                    this.itemDataId = pp.itemDataId
                    this.itemDataNumber = pp.itemDataNumber
                    this.sourceStockUnitId = pp.sourceStockUnitId
                    this.plannedAmount = pp.amount
                    this.state = PickState.RELEASED.code
                    this.lotNumber = pp.lotNumber
                    this.pickingType = pickOrderService.pickingTypeOf(pp, batchSourceAmounts).name
                }
                pickRepository.persist(pick)
            }

            outboxService.publish(
                "PickOrder", batchOrder.id!!, "PickOrderCreated",
                PickOrderCreatedEvent(
                    batchOrder.id!!, batchOrder.pickOrderNumber, null, clientId, zonePicks.size, Instant.now(),
                ),
                clientId,
            )
            batchOrder.id!!
        }
    }

    override fun waveStats(waveId: Long, clientId: Long): WavePickStats {
        val orders = pickOrderRepository.findByWaveId(waveId, clientId)
        val orderIds = orders.mapNotNull { it.id }
        val picks = if (orderIds.isEmpty()) emptyList() else pickRepository.findByPickOrderIds(orderIds)
        return WavePickStats(
            totalPicks = picks.size,
            pickedPicks = picks.count { it.state == PickState.PICKED.code },
            openPickOrders = orders.count { it.state < PickState.PICKED.code },
            totalPickOrders = orders.size,
        )
    }

    /**
     * Mirrors [DefaultPickCancelPort.cancelOpenWorkForDeliveryOrder]'s shape, scoped by `waveId`
     * instead of `deliveryOrderId`: every still-open (pre-PICKED) wave PickOrder is force-finished
     * through [PickLifecycleService.forceFinish] -- the same body the REST pick-order cancel uses.
     */
    @Transactional
    override fun cancelOpenForWave(waveId: Long, clientId: Long): Int =
        pickOrderRepository.findByWaveId(waveId, clientId)
            .filter { it.state < PickState.PICKED.code }
            .onEach { pickLifecycleService.forceFinish(it) }
            .count()

    override fun allTerminal(waveId: Long, clientId: Long): Boolean =
        waveTerminalChecker.allTerminal(waveId, clientId)

    /**
     * Reaches a wave member order's picks via `deliveryOrderLineId`, not `PickOrder
     * .deliveryOrderId` -- a batch (cross-order) PickOrder carries no `deliveryOrderId` of its
     * own, so the line id is the only remaining thread back to the originating order for a
     * batch-routed pick. [DeliveryOrderLookup.findForPicking] is reused rather than adding a new
     * lookup just to enumerate line ids. Explicit clientId since `SortStationService
     * .autoReadyIfComplete` reaches this outside a REST request path.
     */
    override fun openPicksForOrders(deliveryOrderIds: List<Long>, clientId: Long): Int {
        if (deliveryOrderIds.isEmpty()) return 0
        val lineIds = deliveryOrderIds.flatMap { orderId ->
            deliveryOrderLookup.findForPicking(orderId, clientId)?.lines?.map { it.lineId } ?: emptyList()
        }
        return pickRepository.countOpenByLineIds(lineIds, clientId)
    }

    /**
     * Task 2 (Bulk Allocation Sprint A, sort station): every PICKED pick referencing one of
     * [lineIds] that sits on a batch PickOrder, as one [PickedLineSlice] each. Two batched round
     * trips (picks by line id, then their owning PickOrders by id) -- no N+1 regardless of how
     * many lines/picks match. A pick whose PickOrder turns out to be per-order (not a batch,
     * `deliveryOrderId != null`) is silently dropped: it never passed the put wall, so it is not
     * a sort-station concern.
     */
    override fun pickedByLines(lineIds: Collection<Long>, clientId: Long): List<PickedLineSlice> {
        val picks = pickRepository.findPickedByLineIds(lineIds, clientId)
        if (picks.isEmpty()) return emptyList()
        val batchOrders = pickOrderRepository.findByIds(picks.map { it.pickOrderId }.toSet(), clientId)
            .filter { it.deliveryOrderId == null && it.waveId != null }
            .associateBy { it.id!! }
        return picks.mapNotNull { pick -> batchOrders[pick.pickOrderId]?.let { toSlice(pick, it) } }
    }

    /**
     * Task 2 (Bulk Allocation Sprint A, sort station): resolves the batch PickOrder whose pick
     * container is [unitLoadId] (the physical cart) and its PICKED slices -- the sort-station scan
     * of a cart's contents. Reuses [PickRepository.findByPickOrderId] (no client filter): the
     * order itself was already tenant-scoped by [PickOrderRepository.findBatchByTargetUnitLoad].
     */
    override fun cartByUnitLoad(unitLoadId: Long, clientId: Long): BatchCart? {
        val order = pickOrderRepository.findBatchByTargetUnitLoad(unitLoadId, clientId) ?: return null
        val slices = pickRepository.findByPickOrderId(order.id!!)
            .filter { it.state == PickState.PICKED.code && it.deliveryOrderLineId != null }
            .map { toSlice(it, order) }
        return BatchCart(
            pickOrderId = order.id!!, pickOrderNumber = order.pickOrderNumber, waveId = order.waveId!!,
            state = order.state, bulk = order.bulk, unitLoadId = unitLoadId, slices = slices,
        )
    }

    private fun toSlice(pick: Pick, order: PickOrder) = PickedLineSlice(
        deliveryOrderLineId = pick.deliveryOrderLineId!!,
        pickId = pick.id!!,
        pickOrderId = order.id!!,
        itemDataId = pick.itemDataId,
        itemDataNumber = pick.itemDataNumber,
        lotNumber = pick.pickedLotNumber ?: pick.lotNumber,
        pickedAmount = pick.pickedAmount,
        // Final fix wave (F1): 0 was a silent, colliding sentinel -- every container-less batch
        // order would have shared cart id 0, and the sort station now keys its sorted counters by
        // cart. persistBatchPickOrders always stamps a pick container, so a null here is a bug.
        cartUnitLoadId = checkNotNull(order.targetUnitLoadId) { "batch pick order ${order.id} has no pick container" },
        cartStockUnitId = pick.targetStockUnitId,
    )

    companion object {
        private const val UNZONED = "UNZONED"

        /** pick_orders.pick_order_number is VARCHAR(80) -- same ceiling as [PickOrderService]. */
        private const val MAX_NUMBER_LENGTH = 80
    }
}

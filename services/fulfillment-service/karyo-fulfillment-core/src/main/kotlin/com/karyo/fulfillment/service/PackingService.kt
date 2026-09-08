package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickOrderAutoPackEvent
import com.karyo.fulfillment.domain.event.ShipmentStateChangedEvent
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.spi.PackPick
import com.karyo.fulfillment.spi.PackoutContext
import com.karyo.fulfillment.spi.PlannedShippingUnit
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.orders.spi.OrderStrategyLookup
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.event.TransactionPhase
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Packing (3.3): opens a Shipment over a fully-PICKED order, then packs the pick container's
 * confirmed Picks into ShippingUnit(s)/ShippingUnitLine(s) via the resolved [PackoutStrategy].
 * When the packout is complete, flips the container stock PICKED(600)->PACKED(650), advances the
 * Shipment to PACKED and the order to PACKED via the orders seam, and writes an outbox event.
 */
@ApplicationScoped
class PackingService(
    private val shipmentRepository: ShipmentRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val packoutResolver: PackoutStrategyResolver,
    private val orderStrategyLookup: OrderStrategyLookup,
    private val orderProgressionPort: OrderProgressionPort,
    private val deliveryOrderLookup: DeliveryOrderLookup,
    private val stockPicker: StockPicker,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
    private val sequenceNumberService: SequenceNumberService,
    private val consolidatedOrderGuard: ConsolidatedOrderGuard,
) {

    /**
     * Opens packing for a fully-PICKED order: creates the stable parent [Shipment] (PACKING 640),
     * one per order (never one per PickOrder). Idempotency is by rejection -- a second open for an
     * order that already has a shipment is a 409 NotPackable.
     *
     * **Sibling-aware (Task 8 review fix, register row 8).** `createTypeOrders` can split one
     * release into more than one PickOrder, so this reads EVERY pick order for [deliveryOrderId]
     * via [PickOrderRepository.findAllByDeliveryOrderId], not an arbitrary single row. A
     * non-canceled sibling still RELEASED/STARTED refuses packing exactly as a single-order
     * release always did; a canceled sibling is excluded from that check (nothing left to pack
     * for it) the same way [DefaultPickCancelPort] already treats a terminal pick order.
     *
     * **Canceled-order guard (2026-07-31, defect-burndown-2 final gate).** The PickOrder state is
     * not sufficient on its own: a partially-picked order force-finished by
     * [PickLifecycleService.forceFinish] lands on PICKED (correct — "you pack what was picked"),
     * and since the delivery-order cancel cascade force-finishes open pick work, a single
     * `POST /delivery-orders/{id}/cancel` now yields DeliveryOrder=CANCELED **plus** a PICKED
     * PickOrder in one call. Without this check that pair opens packing and ships a canceled
     * order. EXTINGUISH pick orders carry no DeliveryOrder, but they are also unreachable here
     * ([PickOrderRepository.findAllByDeliveryOrderId] matches a non-null id exactly), so the guard
     * never fires for them.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun openPacking(deliveryOrderId: Long): Shipment {
        val clientId = tenantContext.clientId
        val pickOrders = pickOrderRepository.findAllByDeliveryOrderId(deliveryOrderId, clientId)
        val nonCanceledPickOrders = pickOrders.filterNot { it.state == PickState.CANCELED.code }
        if (nonCanceledPickOrders.isEmpty()) {
            consolidatedOrderGuard.requireNotConsolidated(deliveryOrderId, clientId)
            throw FulfillmentException.NotPackable(deliveryOrderId, "no pick order for this order")
        }
        if (deliveryOrderLookup.isCanceled(deliveryOrderId)) {
            throw FulfillmentException.ValidationFailed(
                "delivery order $deliveryOrderId is CANCELED; packing cannot be opened for it",
            )
        }
        if (nonCanceledPickOrders.any { it.state != PickState.PICKED.code }) {
            throw FulfillmentException.NotPackable(deliveryOrderId, "pick order is not fully picked")
        }
        // S4: a CANCELED shipment frees the order -- openPacking can be called again for it.
        val existingShipment = shipmentRepository.findByDeliveryOrderId(deliveryOrderId, clientId)
        if (existingShipment != null && existingShipment.state != ShipmentState.CANCELED.code) {
            throw FulfillmentException.NotPackable(deliveryOrderId, "a shipment already exists")
        }

        // Row 20 (V605): pickOrder.deliveryOrderNumber is now nullable on the entity (EXTINGUISH
        // orders carry no DeliveryOrder), but every entry in nonCanceledPickOrders was found via
        // findAllByDeliveryOrderId(deliveryOrderId, clientId) -- an EXACT match against a caller-
        // supplied non-null Long, which an EXTINGUISH order (null column) can never satisfy. So
        // deliveryOrderNumber is guaranteed non-null here; `!!` documents that invariant rather
        // than fabricating a fallback for a case that cannot occur through this path. Every
        // sibling shares the same deliveryOrderNumber (they all came from the same release), so
        // reading it off the first one is safe.
        val boundDeliveryOrderNumber = nonCanceledPickOrders.first().deliveryOrderNumber!!
        // SC17: was a raw nanoTime tail with no conflict check. shipments.shipment_number is
        // VARCHAR(80) (SC17 V606) -- an over-long embedded order number now surfaces as
        // SequenceException.TooLong (422) instead of a DB 500.
        val shipmentNumber = sequenceNumberService.next(
            "shipment.shipmentNumber", "SHP-$boundDeliveryOrderNumber", clientId, MAX_NUMBER_LENGTH,
        ) { candidate -> shipmentRepository.findByNumber(candidate, clientId) == null }
        val shipment = Shipment().apply {
            this.clientId = clientId
            this.shipmentNumber = shipmentNumber
            this.deliveryOrderId = deliveryOrderId
            this.deliveryOrderNumber = boundDeliveryOrderNumber
            this.state = ShipmentState.PACKING.code
            this.started = Instant.now()
        }
        shipmentRepository.persist(shipment)
        publishState(shipment, 0, ShipmentState.PACKING.code)
        return shipment
    }

    /**
     * Row 8 (`createShippingOrder`): best-effort auto-open, triggered by
     * [PickOrderAutoPackEvent] -- fired synchronously by `PickOrderService.confirmPick` on
     * pick-order completion when the order's strategy has the flag on, but only ACTED ON here
     * once that transaction has committed. A misconfigured strategy must never be able to fail a
     * legitimate pick confirmation -- the auto-open is a convenience; the operator can always
     * still post the shipment by hand.
     *
     * **`@Observes(during = TransactionPhase.AFTER_SUCCESS)` is load-bearing, not decoration**
     * (mirrors `TaskService.onGoodsReceiptLineReceived`, the one other precedent for this exact
     * shape). A same-transaction direct call from `confirmPick` was tried first and does NOT
     * work: [openPacking] re-reads the PickOrder via its own query, and even under
     * [Transactional.TxType.REQUIRES_NEW] that query runs in a transaction that starts before
     * `confirmPick`'s PICKED write commits, so it never sees it -- refuses with "pick order is
     * not fully picked" every single time. Deferring the whole call to AFTER_SUCCESS is what
     * makes the write visible.
     *
     * **[Transactional.TxType.REQUIRES_NEW] is still needed even from AFTER_SUCCESS**, so a
     * refusal here only rolls back this isolated, empty attempt (nothing was persisted before the
     * guard threw), never anything from the (already-committed, by this point) triggering
     * transaction. The catch lives inside this method so the exception never escapes it.
     *
     * **Catches `Exception`, not just [FulfillmentException] (final-review wave, IMPORTANT 4).**
     * [openPacking] also calls `sequenceNumberService.next(...)`, which documents
     * `SequenceException.TooLong` as a real outcome, and `SequenceException` extends
     * `KaryoException`, not `FulfillmentException` -- narrower catch let it escape this
     * best-effort call and fail the legitimate pick confirmation that triggered it, exactly what
     * this method's whole KDoc argues must never happen. The call is best-effort end to end; any
     * failure here is skipped, logged, and never propagated.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun onAutoPackEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) event: PickOrderAutoPackEvent) {
        try {
            openPacking(event.deliveryOrderId)
        } catch (e: Exception) {
            LOG.warnf(e, "createShippingOrder auto-open skipped for DeliveryOrder %d: %s", event.deliveryOrderId, e.message)
        }
    }

    /**
     * Packs the shipment's EVERY sibling pick container into ShippingUnit(s) via the resolved
     * PackoutStrategy (Task 8 review fix, register row 8: `createTypeOrders` can produce more than
     * one PickOrder/container per delivery order, so this can no longer read an arbitrary single
     * row via `findByDeliveryOrderId` -- the other container's picked stock was previously
     * stranded with no path to a shipment). When EVERY sibling's packout completes in this call:
     * each container's stock PICKED->PACKED, Shipment PACKED, order PACKED.
     *
     * **[weight] contract (Task 6 review fix, row :1354).** [weight] is THIS CALL's scale
     * reading -- the weight of whatever is being packed right now, not a running total for the
     * whole shipment. [packSiblingContainers] prorates it across only the picks this call is
     * actually packing (post-ledger remaining work), and every earlier call's already-persisted
     * `ShippingUnit.weight` is left untouched. Concretely: on a repack (e.g. after
     * [ShippingLifecycleService.removeUnit] frees one container's worth of work), the caller
     * weighs and enters the freed quantity's OWN weight, not the shipment's original total --
     * re-entering the full-shipment weight on a repack would double-count the surviving units'
     * weight into the new one(s), inflating the shipment total. See
     * [packSiblingContainers]'s "Weight is PRORATED across siblings" KDoc for the per-call
     * proration mechanics this composes with.
     */
    @Suppress("ThrowsCount", "LongMethod")
    @Transactional
    fun pack(shipmentId: Long, weight: BigDecimal, type: String): Shipment {
        val clientId = tenantContext.clientId
        if (weight.signum() <= 0) {
            throw FulfillmentException.InvalidPackRequest("weight must be > 0")
        }
        val shipment = shipmentRepository.findByIdAndClient(shipmentId, clientId)
            ?: throw FulfillmentException.NotFound("Shipment", shipmentId)
        if (shipment.state != ShipmentState.PACKING.code) {
            throw FulfillmentException.InvalidPackRequest("shipment $shipmentId is not in PACKING")
        }
        requireShipmentNotPaused(shipment)
        // Sprint C: a GROUP (cross-order) shipment has no single bound order -- this per-order
        // pack flow refuses it outright; the wave pack-out routes (Task 2+) own that path.
        val boundOrderId = shipment.deliveryOrderId
            ?: throw FulfillmentException.InvalidPackRequest(
                "shipment $shipmentId is a consolidation-group shipment; pack it through the wave pack-out routes",
            )
        val pickOrders = pickOrderRepository.findAllByDeliveryOrderId(boundOrderId, clientId)
            .filterNot { it.state == PickState.CANCELED.code }
        if (pickOrders.isEmpty()) {
            throw FulfillmentException.NotPackable(boundOrderId, "no pick order")
        }
        val knobs = orderStrategyLookup.findPickingStrategy(boundOrderId)

        val allComplete = packSiblingContainers(
            shipment, boundOrderId, clientId, pickOrders, weight, type, knobs?.packoutStrategy ?: "ONE_TO_ONE",
        )

        if (allComplete) {
            val old = shipment.state
            shipment.state = ShipmentState.PACKED.code
            shipment.finished = Instant.now()
            orderProgressionPort.markPacked(boundOrderId, clientId)
            // Row 8 (sendToShipping): parks the order at SHIPPING(670) after PACKED(650) -- state
            // parking only, never gating the shipment's own state machine (manifest/dispatch are
            // untouched). markShipped later just continues forward from here (progressIfBehind).
            if (knobs?.sendToShipping == true) {
                orderProgressionPort.markShipping(boundOrderId, clientId)
            }
            publishState(shipment, old, ShipmentState.PACKED.code)
        }
        return shipment
    }

    /**
     * Packs every entry in [pickOrders] (already CANCELED-filtered by the caller) into
     * [shipment]'s ShippingUnit(s), one [packoutResolver] call per container -- each pick order
     * owns its own [PickOrder.targetUnitLoadId], so a single [PackoutContext] (one container per
     * call, per the SPI) cannot cover more than one sibling at a time. A container's own stock
     * flips PICKED->PACKED via [stockPicker] as soon as ITS packout completes, independent of
     * whether other siblings in this same call are done -- the shipment/order-level transition in
     * [pack] is gated on every sibling being complete, but an individual container's physical
     * state does not wait on that.
     *
     * **Weight is PRORATED across siblings, not repeated (final-review wave, CRITICAL 1).** The
     * operator enters ONE scalar [weight] for the whole `pack()` call, but a split release can
     * hand this method more than one container. Passing the raw [weight] into every
     * [PackoutContext] unchanged made every sibling's `ShippingUnit` carry the FULL entered
     * weight, multiplying the declared shipment weight by the sibling count -- both
     * `ShippingService.manifest` (carrier billing) and `ShipmentDocumentService` (the BOL) fold
     * every unit's weight into a total, so that total was wrong by a factor of N. Fixed the same
     * way `CartonizationPackout` already prorates a call-scoped weight across its boxes: each
     * container gets `weight * containerPickedAmount / totalPickedAmount`, rounded HALF_UP to 3dp
     * (`shipping_units.weight` is `NUMERIC(12,3)`) -- see [proratedContainerWeight]. A single-
     * container pack (the common case) prorates to exactly the entered weight (ratio 1). This
     * composes correctly with `CartonizationPackout`'s OWN internal proration: that strategy
     * receives this container's already-prorated share as its `context.weight` and further splits
     * IT across its boxes, so the total across every `ShippingUnit` persisted BY THIS CALL still
     * sums (subject to rounding) to [weight] -- i.e. to what the caller entered for THIS call, not
     * to the shipment's grand total (see [pack]'s KDoc for that contract statement and its repack
     * implication). `totalPickedAmount` here is the sum of only the REMAINING (post-ledger) picked
     * amount across siblings THIS call is actually packing -- an already-`fullyConsumed` sibling
     * contributes zero and is excluded from the ratio, so a repack's [weight] prorates purely over
     * the freed quantity, never diluted by (or double-counted against) survivors from an earlier
     * call.
     *
     * **Known caveat (not reachable by either built-in strategy today):** both `ONE_TO_ONE` and
     * `CARTONIZATION` always return `complete = true` in a single call, so a real multi-call
     * incremental packout spanning sibling containers cannot happen yet. If a future strategy ever
     * returns `complete = false`, a container already completed on an earlier [pack] call would be
     * re-resolved (and its `ShippingUnit` re-persisted) on a later call for this same shipment --
     * this method has no per-container "already packed" flag to skip it. Tracking that would need
     * a persisted marker on [PickOrder]; deferred until a strategy actually needs it.
     *
     * **Ledger-based selection (Task 6, row :1354, adjudication A3).** `Pick.state` tops out at
     * `PICKED` -- packing never advances it -- so [PickRepository.findByPickOrderId] returns the
     * SAME picks on every call, forever, whether or not they were already packed. Before this fix,
     * selection was "every still-PICKED pick," so a second `pack()` call on a shipment still (or
     * again) in PACKING -- e.g. after [ShippingLifecycleService.removeUnit]/`.removeLine` regress
     * it, or a hypothetical future incremental strategy -- re-submitted ALREADY-packed picks at
     * their FULL `pickedAmount`, duplicating `ShippingUnitLine` rows and double-flipping container
     * stock. [ShippingUnitLine.sourcePickId] is already a total per-pick ledger (every
     * packout-origin line -- both built-in strategies -- attributes to exactly one pick, never
     * aggregating two picks into one line; verified by construction in [OneToOnePackout] and
     * `CartonizationPackout`'s per-chunk line emission). This method now reads that ledger ONCE per
     * call via [ShippingUnitRepository.consumedAmountsByPick] (one grouped query, not per-pick) and
     * hands the strategy only the REMAINING work: `remaining = pick.pickedAmount -
     * consumed[pick.id]`, filtered to `remaining > 0`, with [PackPick.pickedAmount] set to that
     * remaining amount -- so the SPI's view of "what's left to pack" is exactly right without an
     * interface change. A container is [SiblingContainer.fullyConsumed] (skipped entirely: no
     * [PackoutContext] resolved, no `ShippingUnit` persisted, no [StockPicker.packContainer]
     * call -- a true no-op, still counting toward [allComplete] since nothing is left for it to
     * finish) exactly when it has REAL PICKED picks and every one of them nets to zero remaining.
     * A container with NO picks at all still goes through the strategy unchanged -- that shape
     * is not "already packed," it is "nothing was ever picked into it," a distinct, pre-existing
     * case this fix does not touch. Removal self-heals the ledger for free:
     * [ShippingLifecycleService.removeUnit] hard-deletes a unit's lines and `.removeLine`
     * deletes one line, so the freed amount becomes selectable again on the very next `pack()`
     * call with no extra bookkeeping on this side.
     */
    private fun packSiblingContainers(
        shipment: Shipment,
        deliveryOrderId: Long,
        clientId: Long,
        pickOrders: List<PickOrder>,
        weight: BigDecimal,
        type: String,
        packoutStrategyName: String,
    ): Boolean {
        val consumed = shippingUnitRepository.consumedAmountsByPick(shipment.id!!)
        val containers = pickOrders.map { pickOrder ->
            val container = pickOrder.targetUnitLoadId
                ?: throw FulfillmentException.InvalidPackRequest("pick order has no container")
            val pickedPicks = pickRepository.findByPickOrderId(pickOrder.id!!)
                .filter { it.state == PickState.PICKED.code }
            val packs = pickedPicks.mapNotNull { pick ->
                val remaining = pick.pickedAmount - (consumed[pick.id] ?: BigDecimal.ZERO)
                if (remaining.signum() <= 0) {
                    null
                } else {
                    PackPick(
                        pickId = pick.id!!,
                        itemDataId = pick.itemDataId,
                        itemDataNumber = pick.itemDataNumber,
                        pickedAmount = remaining,
                        lotNumber = pick.lotNumber,
                        sourceStockUnitId = pick.targetStockUnitId,
                    )
                }
            }
            // Distinct from "packs is empty": a container with REAL picks that are all fully
            // consumed already has nothing left to do (skip below). A container with NO real
            // picks at all still goes through the strategy unchanged (pre-existing behavior --
            // see PackingServiceTest's numbering test, which drives a fake strategy that emits
            // units for a pick order with zero Picks).
            val fullyConsumed = pickedPicks.isNotEmpty() && packs.isEmpty()
            val pickedAmount = packs.fold(BigDecimal.ZERO) { acc, p -> acc + p.pickedAmount }
            SiblingContainer(container, packs, pickedAmount, fullyConsumed)
        }
        val totalPickedAmount = containers.fold(BigDecimal.ZERO) { acc, c -> acc + c.pickedAmount }

        var allComplete = true
        containers.forEach { sibling ->
            if (sibling.fullyConsumed) {
                // Nothing remains for this container in this call -- already fully packed by an
                // earlier call. A no-op, not a re-pack: it still counts toward allComplete.
                return@forEach
            }
            val result = packoutResolver.resolve(
                PackoutContext(
                    shipmentId = shipment.id!!,
                    deliveryOrderId = deliveryOrderId,
                    pickContainerUnitLoadId = sibling.container,
                    clientId = clientId,
                    weight = proratedContainerWeight(weight, sibling.pickedAmount, totalPickedAmount),
                    type = type,
                    packoutStrategyName = packoutStrategyName,
                    picks = sibling.packs,
                ),
            )
            persistUnits(shipment, clientId, result.shippingUnits)
            if (result.complete) {
                stockPicker.packContainer(sibling.container, clientId)
            } else {
                allComplete = false
            }
        }
        return allComplete
    }

    /**
     * One sibling pick container's resolved [PackPick]s plus its total picked amount, used to
     * prorate the call-scoped weight across siblings before resolving each [PackoutContext].
     * [fullyConsumed] (Task 6, row :1354) marks a container whose Picks are real but whose
     * entire amount is already reflected in the shipping-unit-line ledger -- see
     * [packSiblingContainers]'s KDoc.
     */
    private data class SiblingContainer(
        val container: Long,
        val packs: List<PackPick>,
        val pickedAmount: BigDecimal,
        val fullyConsumed: Boolean,
    )

    /** Splits [totalWeight] across sibling containers by picked-amount share, same technique and
     *  rounding as `CartonizationPackout.proratedWeight` (HALF_UP to 3dp, matching
     *  `shipping_units.weight NUMERIC(12,3)`). A single container (the common case) always
     *  prorates back to exactly [totalWeight] -- the ratio is 1. */
    private fun proratedContainerWeight(totalWeight: BigDecimal, containerAmount: BigDecimal, totalAmount: BigDecimal): BigDecimal =
        if (totalAmount.signum() == 0) {
            BigDecimal.ZERO.setScale(WEIGHT_SCALE)
        } else {
            totalWeight.multiply(containerAmount).divide(totalAmount, WEIGHT_SCALE, RoundingMode.HALF_UP)
        }

    /**
     * P1 numbering-collision fix + P2 positionIndex synergy (2026-08-15, outbound-completion
     * sprint): `positionIndex` is a per-shipment sequence, not a per-call loop index -- a second
     * `pack()` call on an incomplete packout (`PackoutResult.complete == false`) must continue
     * numbering from where the previous call left off, not restart at 1 and collide on
     * `UNIQUE(client_id, shipping_unit_number)`. Extracted out of [pack] (already 78 lines with
     * `@Suppress("LongMethod")`) rather than growing it further.
     *
     * **`internal` visibility (S5, outbound-completion sprint):** [AdHocShippingUnitService]
     * reuses this exact numbering/persistence machinery for `addAdHocUnit` rather than
     * duplicating it -- "PackingService owns unit creation + numbering" stays true in substance
     * even though `addAdHocUnit` itself lives in a sibling class (a ctor-budget call: this
     * class is already at 12 constructor params, and `addAdHocUnit` needs `UnitLoadLookup` +
     * `StockUnitLookup`, which would push past that; see [AdHocShippingUnitService]'s class KDoc).
     * [origin] defaults to [ShippingUnit.ORIGIN_PACKOUT] so [pack]'s existing call site is
     * unaffected.
     *
     * **Numbering-collision fix (final-review wave, CRITICAL 1, outbound-completion sprint):**
     * the base used to be `findByShipmentId().size`. [ShippingLifecycleService.removeUnit]
     * hard-deletes units, so the surviving count can drop below the highest `positionIndex`
     * ever issued -- a subsequent pack/ad-hoc-attach then reused a number and hit
     * `UNIQUE(client_id, shipping_unit_number)`. The base is now the MAX `positionIndex` seen
     * so far (`?: 0` when the shipment has no units left), a number that only ever goes up.
     *
     * **Root cause closed (Task 6, row :1354).** This fix only ever addressed the SYMPTOM of
     * repeated `pack()` calls -- colliding numbers -- not the underlying duplication: before Task
     * 6, a second call re-submitted already-packed picks at their full amount, so a legitimate
     * repack (after a removal) produced BOTH correct new content AND leftover-content duplicates,
     * all needing distinct numbers. [PackingService.packSiblingContainers]'s ledger-based selection
     * now ensures a call only ever persists genuinely NEW content, so every unit this method
     * numbers is real, not a duplicate. This numbering scheme itself stays exactly as-is -- it is
     * still needed for the legitimate case of a repack adding units alongside survivors.
     */
    internal fun persistUnits(
        shipment: Shipment,
        clientId: Long,
        planned: List<PlannedShippingUnit>,
        origin: String = ShippingUnit.ORIGIN_PACKOUT,
    ) {
        val maxExistingPositionIndex = shippingUnitRepository.findByShipmentId(shipment.id!!)
            .maxOfOrNull { it.positionIndex } ?: 0
        planned.forEachIndexed { offsetInCall, plannedUnit ->
            val positionIndex = maxExistingPositionIndex + offsetInCall + 1
            val unit = ShippingUnit().apply {
                this.clientId = clientId
                this.shipmentId = shipment.id!!
                this.positionIndex = positionIndex
                this.shippingUnitNumber = "${shipment.shipmentNumber}-SU$positionIndex"
                this.type = plannedUnit.type
                this.weight = plannedUnit.weight
                this.state = ShipmentState.PACKED.code
                this.unitLoadId = plannedUnit.unitLoadId
                this.origin = origin
            }
            shippingUnitRepository.persist(unit)
            plannedUnit.lines.forEach { pl ->
                val line = ShippingUnitLine().apply {
                    this.clientId = clientId
                    this.shippingUnitId = unit.id!!
                    this.itemDataId = pl.itemDataId
                    this.itemDataNumber = pl.itemDataNumber
                    this.amount = pl.amount
                    this.sourcePickId = pl.sourcePickId
                    this.lotNumber = pl.lotNumber
                }
                shippingUnitRepository.persistLine(line)
            }
        }
    }

    fun getShipment(id: Long): Shipment =
        shipmentRepository.findByIdAndClient(id, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("Shipment", id)

    fun unitsOf(shipmentId: Long): List<ShippingUnit> =
        shippingUnitRepository.findByShipmentId(shipmentId)

    fun listShipments(): List<Shipment> =
        shipmentRepository.findByClient(tenantContext.clientId)

    private fun publishState(shipment: Shipment, oldState: Int, newState: Int) {
        outboxService.publish(
            "Shipment", shipment.id!!, "ShipmentStateChanged",
            ShipmentStateChangedEvent(
                shipmentId = shipment.id!!,
                shipmentNumber = shipment.shipmentNumber,
                deliveryOrderId = shipment.deliveryOrderId,
                oldState = oldState,
                newState = newState,
                clientId = shipment.clientId,
                occurredAt = Instant.now(),
            ),
            shipment.clientId,
        )
    }

    companion object {
        /** shipments.shipment_number is VARCHAR(80) (SC17 V606 — widened for the embedded order number + sequence tail). */
        private const val MAX_NUMBER_LENGTH = 80

        /** shipping_units.weight is NUMERIC(12,3) -- matches `CartonizationPackout`'s WEIGHT_SCALE. */
        private const val WEIGHT_SCALE = 3
        private val LOG: Logger = Logger.getLogger(PackingService::class.java)
    }
}

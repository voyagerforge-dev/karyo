package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.ShipmentCanceledEvent
import com.karyo.fulfillment.domain.event.ShippingUnitRemovedEvent
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.StockMover
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.api.vo.StockState
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Shipment claim/release/pause/resume lifecycle (S3, outbound-completion sprint), split out of
 * [PackingService]/[ShippingService] to keep both under the ctor budget -- [ShippingService] was
 * already at 9/10 -- mirroring the [PickLifecycleService] split precedent.
 *
 * **operatorId is PURE METADATA**, exactly like [PickOrderService.claim]/[GoodsReceiptService]'s
 * claim: it never moves [Shipment.state]. **pausedAt is the ORTHOGONAL pause scalar** (the
 * GoodsReceipt V422 / TransportOrder PT18 precedent): `state` NEVER moves and
 * [ShipmentState.canAdvanceTo] is untouched, so resume is lossless. The claim is KEPT while
 * paused -- pausing does not release the operator's claim, "paused-is-parked" not "paused-is-
 * abandoned". Both claim/release/pause/resume are deliberately EVENTLESS (no outbox row), matching
 * the GoodsReceipt claim precedent -- pure metadata changes are not domain events.
 *
 * S4 (task 6) added [cancel]/[removeUnit]/[removeLine] to this same service: origin-aware stock
 * restoration ahead of a pre-manifest shipment cancel/unit-removal, gated by
 * [ShipmentState.canAdvanceTo]'s CANCELED window (`< SHIPPING`).
 */
@Suppress("LongParameterList")
@ApplicationScoped
class ShippingLifecycleService(
    private val shipmentRepository: ShipmentRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val pickRepository: PickRepository,
    private val pickOrderRepository: PickOrderRepository,
    private val stockPicker: StockPicker,
    private val stockMover: StockMover,
    private val stockUnitLookup: StockUnitLookup,
    private val unitLoadLookup: UnitLoadLookup,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
) {

    /**
     * Claims [id] for [operatorId]. Refused (409 ValidationFailed) if the shipment is SHIPPED
     * (closed), paused, or already claimed -- mirroring [GoodsReceiptService.claim]'s
     * `requireClaimable` order (closed, then paused, then already-claimed) INCLUDING the paused-
     * refusal fixed there in defect-burndown-4 row 20: a paused shipment refuses claim too, or
     * pausing an unclaimed shipment would let a claim silently succeed and vanish from "mine".
     * Not idempotent: claiming an already-you-claimed shipment is still a 409.
     */
    @Transactional
    fun claim(id: Long, operatorId: String): Shipment {
        val shipment = findEntity(id)
        requireClaimable(shipment, id)
        shipment.operatorId = operatorId
        return shipment
    }

    /** Split into two helpers so neither trips detekt's `ThrowsCount` (max 2), the
     * [GoodsReceiptService.requireClaimable]/`requireNotAlreadyClaimed` precedent. */
    private fun requireClaimable(shipment: Shipment, id: Long) {
        if (shipment.state >= ShipmentState.SHIPPED.code) {
            throw FulfillmentException.ValidationFailed("Shipment $id is closed (state ${shipment.state})")
        }
        if (shipment.pausedAt != null) {
            throw FulfillmentException.ValidationFailed("Shipment $id is paused")
        }
        requireNotAlreadyClaimed(shipment, id)
    }

    private fun requireNotAlreadyClaimed(shipment: Shipment, id: Long) {
        if (shipment.operatorId != null) {
            throw FulfillmentException.ValidationFailed("Shipment $id is already claimed by '${shipment.operatorId}'")
        }
    }

    /**
     * Releases the claim on [id]. A non-owner needs [asManager] (MANAGER role) or the release is
     * a 409 ValidationFailed, never 403 -- the [PickOrderService.release]/[GoodsReceiptService.release]
     * pattern. No state gate, no paused gate: releasing a claim is always allowed regardless of
     * pause (blocking it would strand the claim), matching the GoodsReceipt precedent exactly.
     */
    @Transactional
    fun release(id: Long, operatorId: String, asManager: Boolean): Shipment {
        val shipment = findEntity(id)
        if (shipment.operatorId != operatorId && !asManager) {
            throw FulfillmentException.ValidationFailed("Shipment $id is claimed by a different operator")
        }
        shipment.operatorId = null
        return shipment
    }

    /**
     * Pauses [id]: stamps [Shipment.pausedAt]; `state` and [Shipment.operatorId] are untouched
     * (claim-preserving). Refused (409) once closed (SHIPPED) or already paused -- the same
     * fail-loud non-idempotence as claim. Pausable window is [PACKING, SHIPPED) -- every
     * pre-terminal shipment state.
     */
    @Transactional
    fun pause(id: Long): Shipment {
        val shipment = findEntity(id)
        if (shipment.state >= ShipmentState.SHIPPED.code) {
            throw FulfillmentException.ValidationFailed("Shipment $id is closed (state ${shipment.state})")
        }
        if (shipment.pausedAt != null) {
            throw FulfillmentException.ValidationFailed("Shipment $id is already paused since ${shipment.pausedAt}")
        }
        shipment.pausedAt = Instant.now()
        return shipment
    }

    /** Clears the pause stamp -- resumes exactly where it was (state never moved). 409 if not paused. */
    @Transactional
    fun resume(id: Long): Shipment {
        val shipment = findEntity(id)
        if (shipment.pausedAt == null) {
            throw FulfillmentException.ValidationFailed("Shipment $id is not paused")
        }
        shipment.pausedAt = null
        return shipment
    }

    /**
     * S4: cancels a pre-manifest shipment. Loops every [ShippingUnit] on the shipment and restores
     * its underlying unit-load stock per [ShippingUnit.origin] (via [restoreUnit]), advances the
     * shipment to CANCELED, and fires ONE [ShipmentCanceledEvent] outbox row under the shipment's
     * OWN `clientId` (entity-owner attribution, the [PickLifecycleService.forceFinish] precedent) --
     * not the ambient [TenantContext] one. A canceled shipment frees the delivery order:
     * [PackingService.openPacking]'s duplicate-shipment guard ignores CANCELED shipments, so
     * `openPacking` can be called again for the same order afterward.
     *
     * Refused (409 [FulfillmentException.NotCancelable]) once the shipment has passed PACKED
     * (manifested/shipped) -- [ShipmentState.canAdvanceTo] gates CANCELED the same way, this is
     * just an explicit early guard so the thrown message names the actual blocking state.
     *
     * Group shipments: member DeliveryOrders stay at PACKED, same as the discrete path; a fresh
     * pack-out of the group re-marks them through `progressIfBehind` (burndown-6 A2, WORKLIST row
     * :2066, proven by `ConsolidationPackPortIT`).
     */
    @Transactional
    fun cancel(id: Long): Shipment {
        val shipment = findEntity(id)
        requireCancelable(shipment)
        val units = shippingUnitRepository.findByShipmentId(id)
        units.forEach { restoreUnit(it) }
        shipment.state = ShipmentState.CANCELED.code
        shipment.finished = Instant.now()
        publishCanceled(shipment, units.size)
        return shipment
    }

    /**
     * S4: removes one [ShippingUnit] from a pre-manifest shipment, restoring its unit-load stock
     * per origin (same [restoreUnit] as [cancel]) and hard-deleting the unit and its lines.
     * Removing the LAST unit does NOT delete the shipment -- it stays PACKING, ready to be
     * repacked. If the shipment had already reached PACKED, losing a unit means it needs re-pack:
     * the shipment's `state` is written back to PACKING via a direct field write (NOT through
     * [ShipmentState.canAdvanceTo], which stays forward-only for API-driven transitions) -- an
     * entity-internal regression scoped to exactly this one removal path.
     *
     * **Sole-referent guard (final-review fix wave, CRITICAL 3, outbound-completion sprint).**
     * Every unit produced by a cartonization-style multi-box packout can share the SAME pick
     * container `unitLoadId` (one physical pick, several cartons). [restoreUnit] restores stock
     * by `unitLoadId`, so blindly removing one such unit would flip the WHOLE shared container's
     * stock while its sibling units stay live on the shipment -- corrupting them. [requireSoleReferent]
     * refuses (409 [FulfillmentException.ValidationFailed]) whenever another live unit on the SAME
     * shipment still references [ShippingUnit.unitLoadId]; restoration + deletion only proceed
     * when the unit being removed is the sole referent. The refusal message directs the caller to
     * [cancel] instead, which unwinds every unit on the shipment together.
     *
     * **Root cause closed, guard remains as defense in depth (Task 6, row :1354).** This guard
     * protects [restoreUnit]'s stock-restoration step, not the pack-side selection: before Task 6,
     * a repack after a removal could re-submit already-packed picks at their full amount
     * (`PackingService.pack()` had no per-call consumption marker). `PackingService.
     * packSiblingContainers`'s selection is now ledger-based (remaining = pickedAmount -
     * consumed via `ShippingUnitLine.sourcePickId`), so a repack after [removeUnit] only ever
     * re-packs the quantity THIS removal actually freed, never a live sibling's content. This
     * guard is kept regardless -- it defends the STOCK-restoration invariant (never flip a
     * container's stock while a sibling unit still references it) independently of whether the
     * packing side ever duplicated anything.
     *
     * **Pause guard (IMPORTANT 5, final-review fix wave).** [requireShipmentNotPaused] joins the
     * pack/manifest/dispatch/addAdHocUnit precedent -- removing a unit mutates the shipment same
     * as those do. [cancel] deliberately stays pause-exempt (an abort, like [release]).
     *
     * **Outbox visibility (IMPORTANT 6, final-review fix wave).** Fires ONE `ShippingUnitRemoved`
     * event under the shipment's OWN `clientId` (the [cancel]/[publishCanceled] attribution
     * precedent), carrying the resulting shipment state -- covers the PACKED-to-PACKING regression
     * above too, which was previously invisible outside the direct return value.
     */
    @Transactional
    fun removeUnit(shipmentId: Long, unitId: Long): Shipment {
        val shipment = findEntity(shipmentId)
        requireCancelable(shipment)
        requireShipmentNotPaused(shipment)
        val unit = findUnit(shipmentId, unitId)
        requireSoleReferent(shipment, unit)
        restoreUnit(unit)
        shippingUnitRepository.deleteUnit(unit)
        if (shipment.state == ShipmentState.PACKED.code) {
            shipment.state = ShipmentState.PACKING.code
        }
        publishRemoved(shipment, unit)
        return shipment
    }

    /** See [removeUnit]'s "Sole-referent guard" KDoc. */
    private fun requireSoleReferent(shipment: Shipment, unit: ShippingUnit) {
        val unitLoadId = unit.unitLoadId ?: return
        val sharedWithSibling = shippingUnitRepository.findByShipmentId(shipment.id!!)
            .any { it.id != unit.id && it.unitLoadId == unitLoadId }
        if (sharedWithSibling) {
            throw FulfillmentException.ValidationFailed(
                "ShippingUnit ${unit.id} shares unit load $unitLoadId with another live unit on " +
                    "shipment ${shipment.id}; cancel the shipment to unwind multi-box packs",
            )
        }
    }

    /**
     * S4: removes one [com.karyo.fulfillment.domain.model.ShippingUnitLine] only -- no stock
     * change. Restoration is unit-granularity only (matching [restoreUnit]'s `unitLoadId`-keyed
     * call into [StockPicker.unpackContainer]); a line is pure bookkeeping under its parent unit,
     * so removing one is a plain delete with no side effect on the unit's container stock, even
     * when sibling lines still reference the same unit load.
     *
     * **Pause guard (IMPORTANT 5, final-review fix wave):** [requireShipmentNotPaused] joins
     * [removeUnit]'s same addition, for the same reason -- this mutates the shipment's units too.
     *
     * **Consolidation containers are refused (CRITICAL 1, Sprint C final-review fix wave).** The
     * "a line is pure bookkeeping" reasoning above holds only for a PACKOUT/AD_HOC unit, whose
     * whole container is flipped in place and whose lines merely describe what already sits on it.
     * A [ShippingUnit.ORIGIN_CONSOLIDATION] container is different: its lines are the ONLY record
     * of which cart each slice of quantity was moved off, and [restoreConsolidationUnit] replays
     * exactly those lines to move it back. Deleting one strands that quantity on the container --
     * the cart never gets it back -- and the pack-out ledger
     * ([com.karyo.fulfillment.repository.ShippingUnitRepository.consumedAmountsByPick]) forgets
     * the slice was ever consumed, so the same quantity can be packed a second time. The caller is
     * directed at [removeUnit] (whole container, stock restored) or [cancel] instead.
     */
    @Transactional
    fun removeLine(shipmentId: Long, unitId: Long, lineId: Long): Shipment {
        val shipment = findEntity(shipmentId)
        requireCancelable(shipment)
        requireShipmentNotPaused(shipment)
        requireNotConsolidationContainer(findUnit(shipmentId, unitId))
        val line = shippingUnitRepository.findLineByIdAndClient(lineId, tenantContext.clientId)
            ?.takeIf { it.shippingUnitId == unitId }
            ?: throw FulfillmentException.NotFound("ShippingUnitLine", lineId)
        shippingUnitRepository.deleteLine(line)
        return shipment
    }

    /** See [removeLine]'s "Consolidation containers are refused" KDoc. */
    private fun requireNotConsolidationContainer(unit: ShippingUnit) {
        if (unit.origin == ShippingUnit.ORIGIN_CONSOLIDATION) {
            throw FulfillmentException.ValidationFailed(
                "ShippingUnit ${unit.id} is a consolidation container; remove the whole container " +
                    "or cancel the shipment so its stock returns to the carts",
            )
        }
    }

    /** Pre-manifest window shared by cancel/removeUnit/removeLine -- [ShipmentState.canAdvanceTo]'s CANCELED gate. */
    private fun requireCancelable(shipment: Shipment) {
        if (!ShipmentState.fromCode(shipment.state).canAdvanceTo(ShipmentState.CANCELED)) {
            throw FulfillmentException.NotCancelable(
                shipment.id!!,
                "shipment has passed PACKED (state ${shipment.state})",
            )
        }
    }

    private fun findUnit(shipmentId: Long, unitId: Long): ShippingUnit =
        shippingUnitRepository.findByIdAndClient(unitId, tenantContext.clientId)
            ?.takeIf { it.shipmentId == shipmentId }
            ?: throw FulfillmentException.NotFound("ShippingUnit", unitId)

    /**
     * Restores [unit]'s underlying unit-load stock per [ShippingUnit.origin] via
     * [StockPicker.unpackContainer]: [ShippingUnit.ORIGIN_PACKOUT] -> PICKED(600),
     * [ShippingUnit.ORIGIN_AD_HOC] -> ON_STOCK(300). A no-op if [ShippingUnit.unitLoadId] is
     * null (defensive -- every unit persisted by [PackingService.persistUnits] sets it).
     */
    private fun restoreUnit(unit: ShippingUnit) {
        val unitLoadId = unit.unitLoadId ?: return
        if (unit.origin == ShippingUnit.ORIGIN_CONSOLIDATION) {
            restoreConsolidationUnit(unit, unitLoadId)
            return
        }
        stockPicker.unpackContainer(
            unitLoadId,
            restoreToOnStock = unit.origin == ShippingUnit.ORIGIN_AD_HOC,
            clientId = unit.clientId,
        )
    }

    /**
     * Sprint C: a CONSOLIDATION container's content did not come from one pick container the way
     * a PACKOUT unit's did -- it was MOVED onto this container off one or more batch pick carts,
     * spanning several orders. Restoring it is therefore a genuine reverse move, not an in-place
     * state flip: a closed container is first unpacked (PACKED -> PICKED), then every line's
     * quantity is transferred back to the cart of the pick it came from (that pick's PickOrder
     * `targetUnitLoadId`). An OPEN container is already PICKED and only needs the move-back.
     *
     * A line whose source pick or PickOrder can no longer be resolved is skipped rather than
     * guessed at -- the goods stay on the container, an honest gap surfaced by the container's
     * own remaining stock, never a transfer onto an arbitrary unit load.
     *
     * **The revive result is inspected, not discarded (IMPORTANT 5, Sprint C final-review fix
     * wave).** A cart that the `StockPurgeService` reaper has already hard-deleted cannot receive
     * anything back, and `reviveDrainedContainer` reports that only by returning 0 -- so a purged
     * cart is refused loudly ([requireCartAlive]) rather than left to surface as an opaque
     * downstream failure. A cart that still exists but whose target row stayed DELETABLE is a
     * WARN, not a refusal: the following [StockMover.transferToUnitLoad] is the authority on
     * whether the move is actually possible, and its exception is deliberately NOT swallowed --
     * the whole restore rolls back rather than half-returning a container's stock.
     */
    private fun restoreConsolidationUnit(unit: ShippingUnit, containerUl: Long) {
        if (unit.state != ShippingUnit.STATE_OPEN) {
            stockPicker.unpackContainer(containerUl, restoreToOnStock = false, clientId = unit.clientId)
        }
        val lines = shippingUnitRepository.findLinesByUnitId(unit.id!!)
        val cartByPick = pickRepository.findByIdsAndClient(lines.mapNotNull { it.sourcePickId }.toSet(), unit.clientId)
            .associate { it.id!! to pickOrderRepository.findByIdAndClient(it.pickOrderId, unit.clientId)?.targetUnitLoadId }
        lines.forEach { line ->
            val cartUl = cartByPick[line.sourcePickId] ?: return@forEach
            val source = sourceStockFor(containerUl, unit.clientId, line) ?: return@forEach
            requireCartAlive(unit, cartUl)
            // The pack-out DRAINED this cart, so inventory tombstoned both the cart unit load and
            // this item/lot's emptied stock row DELETABLE -- correct for a finished container,
            // fatal for one being refilled (a DELETABLE target unit load is refused outright).
            // Targeted at THIS line's item+lot so a multi-SKU cart's other tombstones stay
            // tombstoned; idempotent no-op when the cart is still live, e.g. a partially packed
            // one, or when a sibling line already revived the same row.
            val revived = stockPicker.reviveDrainedContainer(cartUl, line.itemDataId, line.lotNumber, unit.clientId)
            if (revived == 0) warnIfStillTombstoned(unit, cartUl, line)
            stockMover.transferToUnitLoad(source, cartUl, line.amount, ACTIVITY_UNPACK, unit.clientId)
        }
    }

    /** See [restoreConsolidationUnit]'s "revive result is inspected" KDoc -- the purged-cart half. */
    private fun requireCartAlive(unit: ShippingUnit, cartUl: Long) {
        if (unitLoadLookup.findById(cartUl, unit.clientId) == null) {
            throw FulfillmentException.ValidationFailed(
                "cart unit load $cartUl of shipment ${unit.shipmentId} no longer exists; " +
                    "the container's stock cannot be returned",
            )
        }
    }

    /** See [restoreConsolidationUnit]'s "revive result is inspected" KDoc -- the WARN half. */
    private fun warnIfStillTombstoned(unit: ShippingUnit, cartUl: Long, line: ShippingUnitLine) {
        val stillTombstoned = stockUnitLookup.findByUnitLoadId(cartUl, unit.clientId).any {
            it.itemDataId == line.itemDataId &&
                (it.lotNumber ?: "") == (line.lotNumber ?: "") &&
                it.state == StockState.DELETABLE.code
        }
        if (stillTombstoned) {
            LOG.warnf(
                "Consolidation restore of shipment %d: cart unit load %d still holds a DELETABLE " +
                    "stock row for item %d lot '%s' that the revive did not bring back",
                unit.shipmentId, cartUl, line.itemDataId, line.lotNumber ?: "",
            )
        }
    }

    /**
     * The stock unit on [containerUl] that this line's quantity should come off: the same
     * item/lot with enough on it, falling back to any same-item row (the container aggregates,
     * so a line's own row is normally merged into one). Re-read per line because each transfer
     * changes the amounts. Null when the container no longer carries that item at all.
     */
    private fun sourceStockFor(containerUl: Long, clientId: Long, line: ShippingUnitLine): Long? {
        val onContainer = stockUnitLookup.findByUnitLoadId(containerUl, clientId).filter { it.amount.signum() > 0 }
        val sameLot = onContainer.firstOrNull {
            it.itemDataId == line.itemDataId &&
                (it.lotNumber ?: "") == (line.lotNumber ?: "") &&
                it.amount >= line.amount
        }
        return (sameLot ?: onContainer.firstOrNull { it.itemDataId == line.itemDataId })?.id
    }

    private fun publishCanceled(shipment: Shipment, restoredUnitCount: Int) {
        outboxService.publish(
            "Shipment", shipment.id!!, "ShipmentCanceled",
            ShipmentCanceledEvent(
                shipmentId = shipment.id!!,
                shipmentNumber = shipment.shipmentNumber,
                deliveryOrderId = shipment.deliveryOrderId,
                clientId = shipment.clientId,
                restoredUnitCount = restoredUnitCount,
                occurredAt = Instant.now(),
            ),
            shipment.clientId,
        )
    }

    /** See [removeUnit]'s "Outbox visibility" KDoc. */
    private fun publishRemoved(shipment: Shipment, unit: ShippingUnit) {
        outboxService.publish(
            "Shipment", shipment.id!!, "ShippingUnitRemoved",
            ShippingUnitRemovedEvent(
                shipmentId = shipment.id!!,
                shippingUnitId = unit.id!!,
                shippingUnitNumber = unit.shippingUnitNumber,
                resultingState = shipment.state,
                clientId = shipment.clientId,
                occurredAt = Instant.now(),
            ),
            shipment.clientId,
        )
    }

    private fun findEntity(id: Long): Shipment =
        shipmentRepository.findByIdAndClient(id, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("Shipment", id)

    private companion object {
        /** Journal activity code for the reverse move, matching [StockPicker.unpackContainer]'s. */
        const val ACTIVITY_UNPACK = "UNPACK"

        val LOG: Logger = Logger.getLogger(ShippingLifecycleService::class.java)
    }
}

/**
 * Shared precondition guard for [PackingService.pack] and [ShippingService.manifest]/
 * [ShippingService.dispatch] -- ONE place for the "paused shipment refuses pack/manifest/
 * dispatch" rule instead of triple-duplicating the check (the emitter-extraction lesson).
 *
 * Deliberately STATELESS (a top-level function, not an instance method on
 * [ShippingLifecycleService]): the caller already has the fetched [Shipment] in hand, so this
 * needs no repository/tenant lookup of its own. Keeping it stateless means [PackingService] and
 * [ShippingService] don't have to inject [ShippingLifecycleService] just to reach a pure
 * predicate -- their constructors stay at their pre-Task-5 sizes (12 and 9 params respectively).
 */
fun requireShipmentNotPaused(shipment: Shipment) {
    if (shipment.pausedAt != null) {
        throw FulfillmentException.ValidationFailed("Shipment ${shipment.id} is paused")
    }
}

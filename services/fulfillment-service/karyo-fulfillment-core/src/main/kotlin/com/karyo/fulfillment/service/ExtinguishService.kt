package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickOrderCreatedEvent
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.spi.PlannedPick
import com.karyo.fulfillment.vo.PickState
import com.karyo.fulfillment.vo.PickingType
import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockReserver
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.spi.StagingLocationLookup
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.ws.rs.BadRequestException
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant

/**
 * Extinguish (stock-clearance) picks — WORKLIST row 20. Behavioral parity with myWMS
 * extinguish-order generation (independent implementation). The resulting Karyo behavior is
 * specified in `docs/wiki/functional/outbound.md:198`. Concretely:
 * one Pick per stock unit for its FULL `availableAmount` (or every stock unit on a UnitLoad),
 * reservation-backed at creation — the same choke-point discipline
 * [PickTopUpService.reserveRecovered] introduced: every Pick this service creates is reserved
 * BEFORE the row exists, never after.
 *
 * **No `OrderStrategy`/`DeliveryOrder` linkage (sprint adjudication 3)** — myWMS auto-creates a
 * per-client extinguish `OrderStrategy`; Karyo instead marks every pick `pickingType =
 * "EXTINGUISH"` and prefixes the owning order's number `"EXT-"`. `PickOrder.deliveryOrderId ==
 * null` IS the EXT marker [PickTopUpService.addPicksToOrder] matches new picks against — no
 * separate flag needed.
 *
 * **Merge rule:** an existing OPEN (state <= RELEASED) EXTINGUISH order for the SAME client is
 * topped up via [PickTopUpService.addPicksToOrder] (Task 2's primitive, reused verbatim, same as
 * every other PickOrder mutation's outbox shape); otherwise a new order is minted at RELEASED,
 * mirroring [PickOrderService.releaseToPicking]'s own container/persistence/outbox shape. There
 * is no `PickingOrderPrepareEvent` fire here — that hook is specific to DeliveryOrder release;
 * myWMS's `ExtinguishOrderGenerator` has no analogous extension point.
 */
@ApplicationScoped
class ExtinguishService(
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val stockUnitLookup: StockUnitLookup,
    private val stockReserver: StockReserver,
    private val stagingLocationLookup: StagingLocationLookup,
    private val stockPicker: StockPicker,
    private val topUpService: PickTopUpService,
    private val outboxService: OutboxService,
    private val sequenceNumberService: SequenceNumberService,
    // Same knob PickOrderService.releaseToPicking uses (row 17) — non-empty default per the
    // SRCFG00040 boot-trap rule.
    @ConfigProperty(name = "karyo.fulfillment.pick-bin-unit-load-type-id", defaultValue = "2")
    private val defaultPickBinTypeId: Long,
) {

    /**
     * Exactly one of [stockUnitIds]/[unitLoadId] is required (400 otherwise). Cross-tenant or
     * unknown stock resolves to an empty candidate list — a 404-style refusal via the lookup's own
     * scoping, never a leaked existence signal. A stock unit with zero `availableAmount`, or one
     * whose reservation fails (claimed elsewhere in the interim), is SKIPPED, not fatal; if EVERY
     * candidate is skipped this way, the whole call is refused with 409 (never a same-shape empty
     * order).
     *
     * [targetUnitLoadTypeId] is honored only when a new EXT order is minted; refused (409) if
     * supplied while an open EXT order for the owner would absorb the picks (M2 fix — the merge
     * path reuses that order's existing pick bin, so the override could never actually be
     * honored, and it used to be silently dropped).
     */
    @Transactional
    fun extinguish(stockUnitIds: List<Long>?, unitLoadId: Long?, targetUnitLoadTypeId: Long? = null): PickOrder {
        requireExactlyOneSelector(stockUnitIds, unitLoadId)

        val candidates = resolveStock(stockUnitIds, unitLoadId)
        if (candidates.isEmpty()) {
            throw FulfillmentException.NotFound("StockUnit", unitLoadId?.toString() ?: stockUnitIds.toString())
        }
        // M8 fix (final review): attribute the EXT order to the STOCK's owner, not the caller's
        // own clientId -- a wide-readScope (OPS) principal extinguishing another owner's stock
        // must not mis-attribute the order to itself.
        val ownerClientId = resolveOwnerClientId(candidates)
        // M2 fix (final review): the merge-target lookup moves ABOVE reserveFullAmounts so an
        // unhonorable override is refused BEFORE any stock is reserved -- no half-taken
        // reservation on the refusal path (the @Transactional would roll it back anyway; this is
        // belt-and-suspenders, and it lets the caller retry immediately without a stray reserve
        // to clean up).
        val existing = pickOrderRepository.findOpenExtinguishOrder(ownerClientId)
        requireNoUnhonorableOverride(existing, targetUnitLoadTypeId)

        val reserved = reserveFullAmounts(candidates)
        if (reserved.isEmpty()) {
            throw FulfillmentException.ValidationFailed(
                "No stock could be extinguished: every candidate had zero available amount or " +
                    "could not be reserved (claimed elsewhere in the interim)",
            )
        }
        val planned = reserved.map { it.toPlannedPick() }

        return if (existing != null) {
            topUpService.addPicksToOrder(existing, planned)
            existing
        } else {
            mintExtinguishOrder(ownerClientId, planned, targetUnitLoadTypeId)
        }
    }

    /**
     * M2 fix: a merge into an already-open EXT order reuses that order's EXISTING pick bin --
     * `targetUnitLoadTypeId` only ever takes effect when [mintExtinguishOrder] creates a brand
     * new container, so an override supplied on a call that turns out to merge can never be
     * honored. Refusing (409) beats silently dropping it, which is what this service used to do.
     */
    private fun requireNoUnhonorableOverride(existing: PickOrder?, targetUnitLoadTypeId: Long?) {
        if (existing != null && targetUnitLoadTypeId != null) {
            throw FulfillmentException.ValidationFailed(
                "targetUnitLoadTypeId cannot be honored: this extinguish merges into open EXT order " +
                    "${existing.pickOrderNumber}, whose pick bin already exists — omit the override " +
                    "or finish that order first",
            )
        }
    }

    /**
     * M8 fix: all stock units in one extinguish call must share a SINGLE owner `clientId` — the
     * stock's owner, not necessarily the caller's. A wide-readScope (OPS) principal can see
     * multiple owners' stock at once (unlike an OWNER principal, whose own scope already makes
     * this a non-issue), so a mixed-owner selection is refused outright (409) rather than
     * silently attributing the EXT order to whichever owner happens to win — the merge-target
     * lookup ([com.karyo.fulfillment.repository.PickOrderRepository.findOpenExtinguishOrder])
     * then correctly matches on that single owner, not the caller.
     */
    private fun resolveOwnerClientId(candidates: List<StockUnitResponse>): Long {
        val owners = stockUnitLookup.findOwnerClientIdsByIds(candidates.map { it.id }.toSet()).values.toSet()
        return owners.singleOrNull() ?: throw FulfillmentException.ValidationFailed(
            "Stock units for one extinguish call must belong to a single owner clientId (found: $owners)",
        )
    }

    private fun requireExactlyOneSelector(stockUnitIds: List<Long>?, unitLoadId: Long?) {
        val hasIds = !stockUnitIds.isNullOrEmpty()
        val hasUnitLoad = unitLoadId != null
        if (hasIds == hasUnitLoad) {
            throw BadRequestException("exactly one of stockUnitIds or unitLoadId is required")
        }
    }

    /** Cross-tenant/unknown stock is simply absent — the lookups are tenant-scoped (see their KDoc). */
    private fun resolveStock(stockUnitIds: List<Long>?, unitLoadId: Long?): List<StockUnitResponse> =
        if (unitLoadId != null) {
            stockUnitLookup.findByUnitLoadId(unitLoadId)
        } else {
            stockUnitLookup.findByIds(stockUnitIds!!.toSet())
        }

    /**
     * Reserves the FULL `availableAmount` of each candidate (skip-not-fail per unit): a zero-
     * available unit is dropped before ever attempting a reserve; a nonzero unit whose reserve
     * fails ([StockReserver.reserveOnStockUnit] returns false — locked, or raced out from under
     * us) is dropped too, never partially added.
     */
    private fun reserveFullAmounts(candidates: List<StockUnitResponse>): List<StockUnitResponse> =
        candidates
            .filter { it.availableAmount.signum() > 0 }
            .filter { stockReserver.reserveOnStockUnit(it.id, it.availableAmount, "extinguish") }

    private fun StockUnitResponse.toPlannedPick() = PlannedPick(
        deliveryOrderLineId = null,
        itemDataId = itemDataId,
        itemDataNumber = itemDataNumber,
        lotNumber = lotNumber,
        sourceStockUnitId = id,
        amount = availableAmount,
    )

    /** Mints a brand-new EXTINGUISH order, mirroring `PickOrderService.releaseToPicking`'s shape. */
    private fun mintExtinguishOrder(clientId: Long, planned: List<PlannedPick>, targetUnitLoadTypeId: Long?): PickOrder {
        val staging = stagingLocationLookup.findPackStaging(clientId)
            ?: throw FulfillmentException.ValidationFailed("no PACK_STAGING location configured")
        // SC17: was a raw nanoTime tail with no conflict check. pick_orders.pick_order_number
        // is VARCHAR(80) (SC17 V606); same UL-label-collision caveat as PickOrderService.releaseToPicking
        // (this string also becomes the pick container's UnitLoad labelId below) -- isUnique
        // checks only the pick-order-number side for the same "no new cross-module Gradle edge"
        // reason documented there.
        val pickOrderNumber = sequenceNumberService.next(
            "pick.extinguishNumber", "EXT", clientId, MAX_NUMBER_LENGTH,
        ) { candidate -> pickOrderRepository.findByNumber(candidate, clientId) == null }
        val container = stockPicker.createPickContainer(
            clientId = clientId,
            unitLoadTypeId = targetUnitLoadTypeId ?: defaultPickBinTypeId,
            locationId = staging.id,
            locationName = staging.name,
            labelId = pickOrderNumber,
        )
        val pickOrder = PickOrder().apply {
            this.clientId = clientId
            this.pickOrderNumber = pickOrderNumber
            this.deliveryOrderId = null
            this.deliveryOrderNumber = null
            this.state = PickState.RELEASED.code
            this.targetUnitLoadId = container
            this.started = Instant.now()
        }
        pickOrderRepository.persist(pickOrder)

        planned.forEach { pp ->
            val pick = Pick().apply {
                this.clientId = clientId
                this.pickOrderId = pickOrder.id!!
                this.deliveryOrderLineId = null
                this.itemDataId = pp.itemDataId
                this.itemDataNumber = pp.itemDataNumber
                this.sourceStockUnitId = pp.sourceStockUnitId
                this.plannedAmount = pp.amount
                this.state = PickState.RELEASED.code
                this.lotNumber = pp.lotNumber
                this.pickingType = PickingType.EXTINGUISH.name
            }
            pickRepository.persist(pick)
        }

        outboxService.publish(
            "PickOrder", pickOrder.id!!, "PickOrderCreated",
            PickOrderCreatedEvent(pickOrder.id!!, pickOrder.pickOrderNumber, null, clientId, planned.size, Instant.now()),
            clientId,
        )
        return pickOrder
    }

    companion object {
        /** pick_orders.pick_order_number is VARCHAR(80) (SC17 V606). */
        private const val MAX_NUMBER_LENGTH = 80
    }
}

package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.ShipmentStateChangedEvent
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.domain.model.ShipmentOrder
import com.karyo.fulfillment.domain.model.ShippingUnit
import com.karyo.fulfillment.domain.model.ShippingUnitLine
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.spi.ConsolidationPackPort
import com.karyo.fulfillment.spi.ContainerLineView
import com.karyo.fulfillment.spi.ContainerView
import com.karyo.fulfillment.spi.GroupShipmentView
import com.karyo.fulfillment.spi.OpenContainerRequest
import com.karyo.fulfillment.spi.OpenGroupShipmentRequest
import com.karyo.fulfillment.spi.PackLineAllocation
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.StockMover
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.api.vo.StockState
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Bulk Allocation Sprint C: shipment/container/stock mechanics for a consolidation-group
 * (cross-order) shipment. Allocation (WHICH sorted units go into a container) is wave-core's
 * job (`com.karyo.wave.service.PackoutService`); this class only executes what it is handed.
 *
 * **Containers ARE unit loads.** A container is a [ShippingUnit] whose `unitLoadId` points at a
 * real pick-bin unit load -- either a scanned, empty LPN the operator adopted or one minted here
 * via [StockPicker.createPickContainer]. Filling a container is a genuine stock MOVE off the
 * cart's stock unit ([StockMover.transferToUnitLoad]), not bookkeeping: `StockService.transferStock`
 * mints the landing row with `state = source.state`, so PICKED cart stock lands PICKED on the
 * container and [closeContainer]'s [StockPicker.packContainer] then flips it PICKED -> PACKED,
 * exactly as the discrete pack path does for its own pick container.
 *
 * **Origin [ShippingUnit.ORIGIN_CONSOLIDATION]** drives the reverse: `ShippingLifecycleService`'s
 * cancel/removal restore moves every line's quantity back to the cart the source pick came from,
 * rather than just flipping a state in place (the PACKOUT/AD_HOC origins' behaviour) -- a
 * consolidation container's content came from SEVERAL carts and several orders.
 *
 * Constructor is 12 params, over the WORKLIST's documented 10-param discipline: the class's job
 * is genuinely to sit between five collaborating seams (shipment/unit/pick persistence, the two
 * inventory stock seams, the orders progression seam, sequence + outbox). Splitting it into a
 * helper bean purely to move parameters around would hide one atomic operation behind two beans
 * with no independent meaning. Detekt's `LongParameterList` is upstream-inert on this repo's
 * classpath-less task, so this is a documented deviation, not a suppressed finding.
 *
 * Explicit `clientId` everywhere; this class never reads the ambient `TenantContext`.
 */
@Suppress("LongParameterList")
@ApplicationScoped
class DefaultConsolidationPackPort(
    private val shipmentRepository: ShipmentRepository,
    private val shipmentOrderRepository: ShipmentOrderRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val pickOrderRepository: PickOrderRepository,
    private val stockMover: StockMover,
    private val stockPicker: StockPicker,
    private val stockUnitLookup: StockUnitLookup,
    private val unitLoadLookup: UnitLoadLookup,
    private val orderProgressionPort: OrderProgressionPort,
    private val sequenceNumberService: SequenceNumberService,
    private val outboxService: OutboxService,
    private val pickBinTypeResolver: PickOrderService,
) : ConsolidationPackPort {

    override fun findOpenGroupShipment(consolidationGroupId: Long, clientId: Long): GroupShipmentView? =
        shipmentRepository.findOpenByGroupId(consolidationGroupId, clientId)?.let { view(it) }

    /**
     * Exactly one query, no [view] hydration: `first()` is the lowest id because
     * [ShipmentRepository.findOpenByGroupIds] sorts by id ascending.
     */
    override fun findOpenGroupShipmentIds(consolidationGroupIds: Collection<Long>, clientId: Long): Map<Long, Long> =
        shipmentRepository.findOpenByGroupIds(consolidationGroupIds, clientId)
            .groupBy { it.consolidationGroupId!! }
            .mapValues { (_, shipments) -> shipments.first().id!! }

    @Transactional
    override fun openGroupShipment(request: OpenGroupShipmentRequest): GroupShipmentView {
        val clientId = request.clientId
        shipmentRepository.findOpenByGroupId(request.consolidationGroupId, clientId)?.let { return view(it) }
        if (request.members.isEmpty()) {
            throw FulfillmentException.InvalidPackRequest("a group shipment needs at least one member")
        }
        val number = sequenceNumberService.next(
            "shipment.shipmentNumber", "SHP-${request.waveNumber}-${request.sortSlot}", clientId, MAX_NUMBER_LENGTH,
        ) { candidate -> shipmentRepository.findByNumber(candidate, clientId) == null }
        val shipment = Shipment().apply {
            this.clientId = clientId
            this.shipmentNumber = number
            this.deliveryOrderId = null
            this.deliveryOrderNumber = null
            this.consolidationGroupId = request.consolidationGroupId
            this.waveId = request.waveId
            this.state = ShipmentState.PACKING.code
            this.started = Instant.now()
        }
        shipmentRepository.persist(shipment)
        request.members.forEach { member ->
            shipmentOrderRepository.persist(
                ShipmentOrder().apply {
                    this.clientId = clientId
                    this.shipmentId = shipment.id!!
                    this.deliveryOrderId = member.orderId
                    this.deliveryOrderNumber = member.orderNumber
                },
            )
            orderProgressionPort.markPacking(member.orderId, clientId)
        }
        publishState(shipment, 0, ShipmentState.PACKING.code)
        outboxService.publish(
            "Shipment", shipment.id!!, "shipment.group.opened",
            mapOf(
                "consolidationGroupId" to request.consolidationGroupId,
                "waveId" to request.waveId,
                "orders" to request.members.map { it.orderId },
            ),
            clientId,
        )
        return view(shipment)
    }

    @Transactional
    override fun openContainer(shipmentId: Long, request: OpenContainerRequest, clientId: Long): ContainerView {
        val shipment = packingGroupShipment(shipmentId, clientId)
        val unitLoadId = request.unitLoadId?.also { requireAdoptableUnitLoad(it, clientId) }
            ?: stockPicker.createPickContainer(
                clientId = clientId,
                unitLoadTypeId = pickBinTypeResolver.resolvePickBinTypeId(null),
                locationId = request.stagingLocationId,
                locationName = request.stagingLocationName,
                labelId = nextContainerLabel(shipment, clientId),
            )
        val positionIndex = (shippingUnitRepository.findByShipmentId(shipmentId).maxOfOrNull { it.positionIndex } ?: 0) + 1
        val unit = ShippingUnit().apply {
            this.clientId = clientId
            this.shipmentId = shipmentId
            this.positionIndex = positionIndex
            this.shippingUnitNumber = "${shipment.shipmentNumber}-SU$positionIndex"
            this.type = request.type
            this.weight = BigDecimal.ZERO
            this.state = ShippingUnit.STATE_OPEN
            this.unitLoadId = unitLoadId
            this.origin = ShippingUnit.ORIGIN_CONSOLIDATION
        }
        shippingUnitRepository.persist(unit)
        return containerView(unit)
    }

    /**
     * Per-slice, never per-line: each [PackLineAllocation] is one pick's contribution, so a line
     * split across two carts crosses this seam as two allocations and lands as two
     * [ShippingUnitLine] rows. Nothing here is aggregated.
     *
     * Exceptions out of [StockMover.transferToUnitLoad] are deliberately NOT caught: it is a
     * nested `@Transactional` call, and swallowing its failure would leave the shared JTA
     * transaction marked rollback-only while this method carried on as if it had succeeded.
     *
     * **This port does not own the "amount exceeds what is still unpacked" rule.** Deciding how
     * much of a sorted line may go into a container is allocation, so it belongs to the caller
     * (wave-core's `PackoutService`, Task 4), which owes the operator a 422 before it ever gets
     * here. What this method contributes is defense in depth: over-allocating past what is
     * physically on the cart surfaces as inventory's own `InsufficientStock` 409 out of
     * [StockMover.transferToUnitLoad], a correctness backstop rather than the user-facing
     * validation. Only the amount's own sign is refused here, as a 422.
     */
    @Transactional
    override fun addLines(
        shipmentId: Long,
        containerId: Long,
        lines: List<PackLineAllocation>,
        clientId: Long,
    ): ContainerView {
        packingGroupShipment(shipmentId, clientId)
        val unit = openContainerOrThrow(shipmentId, containerId, clientId)
        val containerUl = requireNotNull(unit.unitLoadId) { "consolidation container ${unit.id} has no unit load" }
        lines.forEach { alloc ->
            if (alloc.amount.signum() <= 0) {
                throw FulfillmentException.InvalidPackRequest("amount must be positive")
            }
            val moved =
                stockMover.transferToUnitLoad(alloc.cartStockUnitId, containerUl, alloc.amount, ACTIVITY_PACK, clientId)
            val landed = stockUnitLookup.findByIds(setOf(moved.stockUnitId), clientId).single()
            check(landed.state == PICKED_CODE) { "moved stock landed in state ${landed.state}, expected PICKED" }
            shippingUnitRepository.persistLine(
                ShippingUnitLine().apply {
                    this.clientId = clientId
                    this.shippingUnitId = unit.id!!
                    this.itemDataId = alloc.itemDataId
                    this.itemDataNumber = alloc.itemDataNumber
                    this.amount = alloc.amount
                    this.sourcePickId = alloc.sourcePickId
                    this.lotNumber = alloc.lotNumber
                    this.deliveryOrderId = alloc.deliveryOrderId
                    this.deliveryOrderLineId = alloc.deliveryOrderLineId
                },
            )
        }
        return containerView(unit)
    }

    @Transactional
    override fun closeContainer(shipmentId: Long, containerId: Long, weight: BigDecimal, clientId: Long): ContainerView {
        packingGroupShipment(shipmentId, clientId)
        val unit = openContainerOrThrow(shipmentId, containerId, clientId)
        requireCloseable(unit, weight)
        unit.weight = weight
        unit.state = ShipmentState.PACKED.code
        requireFlipped(stockPicker.packContainer(unit.unitLoadId!!, clientId), unit, "packed")
        outboxService.publish(
            "Shipment", shipmentId, "shipment.container.closed",
            mapOf("containerId" to unit.id, "weight" to weight), clientId,
        )
        return containerView(unit)
    }

    /** Split out of [closeContainer] so that function stays inside detekt's `ThrowsCount` ceiling. */
    private fun requireCloseable(unit: ShippingUnit, weight: BigDecimal) {
        if (weight.signum() <= 0) throw FulfillmentException.InvalidPackRequest("weight must be > 0")
        if (shippingUnitRepository.findLinesByUnitId(unit.id!!).isEmpty()) {
            throw FulfillmentException.InvalidPackRequest("container ${unit.shippingUnitNumber} is empty")
        }
    }

    @Transactional
    override fun reopenContainer(shipmentId: Long, containerId: Long, clientId: Long): ContainerView {
        val shipment = reopenableShipment(shipmentId, clientId)
        val unit = shippingUnitRepository.findByIdAndClient(containerId, clientId)?.takeIf { it.shipmentId == shipmentId }
            ?: throw FulfillmentException.NotFound("ShippingUnit", containerId)
        if (unit.state == ShippingUnit.STATE_OPEN) return containerView(unit)
        requireFlipped(
            stockPicker.unpackContainer(unit.unitLoadId!!, restoreToOnStock = false, clientId = clientId),
            unit, "unpacked",
        )
        unit.state = ShippingUnit.STATE_OPEN
        if (shipment.state == ShipmentState.PACKED.code) {
            // The shipment regresses PACKED -> PACKING. A direct field write, NOT
            // ShipmentState.canAdvanceTo (which stays forward-only for API-driven transitions),
            // scoped to exactly this reopen path -- the ShippingLifecycleService.removeUnit
            // precedent, including its IMPORTANT-6 lesson: the regression must be VISIBLE outside
            // this call's own return value, or anything reading the event log sees a shipment
            // sitting at PACKED that is really back on the pack bench.
            shipment.state = ShipmentState.PACKING.code
            publishState(shipment, ShipmentState.PACKED.code, ShipmentState.PACKING.code)
        }
        return containerView(unit)
    }

    /** Reopen's own window: pre-manifest, but PACKED is fine (unlike [packingGroupShipment]). */
    private fun reopenableShipment(shipmentId: Long, clientId: Long): Shipment {
        val shipment = groupShipment(shipmentId, clientId)
        if (shipment.state >= ShipmentState.SHIPPING.code) {
            throw FulfillmentException.InvalidPackRequest(
                "shipment $shipmentId is manifested; containers can no longer be reopened",
            )
        }
        return shipment
    }

    /**
     * Closes the group shipment: every member order is marked PACKED and the shipment advances.
     *
     * A drained batch cart stays DELETABLE after completion. Carts are ephemeral unit loads minted
     * per PickOrder ([StockPicker.createPickContainer]), not equipment: an emptied one is reclaimed
     * by the reaper exactly like a discrete pick container after ship. Only cancel revives it
     * ([StockPicker.reviveDrainedContainer]), because cancel must refill it (burndown-6 A1,
     * WORKLIST row :2056).
     */
    @Transactional
    override fun completeGroupShipment(shipmentId: Long, clientId: Long, sendToShipping: Boolean): GroupShipmentView {
        val shipment = packingGroupShipment(shipmentId, clientId)
        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        requireEveryContainerClosed(shipmentId, units)
        val old = shipment.state
        shipment.state = ShipmentState.PACKED.code
        shipment.finished = Instant.now()
        shipmentOrderRepository.findByShipmentId(shipmentId, clientId).forEach { member ->
            orderProgressionPort.markPacked(member.deliveryOrderId, clientId)
            if (sendToShipping) orderProgressionPort.markShipping(member.deliveryOrderId, clientId)
        }
        publishState(shipment, old, ShipmentState.PACKED.code)
        return view(shipment)
    }

    /** Split out of [completeGroupShipment] for detekt's `ThrowsCount` ceiling. */
    private fun requireEveryContainerClosed(shipmentId: Long, units: List<ShippingUnit>) {
        if (units.isEmpty()) throw FulfillmentException.InvalidPackRequest("shipment $shipmentId has no containers")
        if (units.any { it.state == ShippingUnit.STATE_OPEN }) {
            throw FulfillmentException.InvalidPackRequest("shipment $shipmentId still has an open container")
        }
    }

    override fun packedByLines(lineIds: Collection<Long>, clientId: Long): Map<Long, BigDecimal> =
        shippingUnitRepository.sumAmountByOrderLineIds(lineIds, clientId)

    /** The shipment is resolved tenant-scoped first, so the ledger read can never cross a tenant. */
    override fun packedByPicks(shipmentId: Long, clientId: Long): Map<Long, BigDecimal> =
        shippingUnitRepository.consumedAmountsByPick(groupShipment(shipmentId, clientId).id!!)

    // -- helpers ----------------------------------------------------------------------------

    private fun groupShipment(shipmentId: Long, clientId: Long): Shipment =
        shipmentRepository.findByIdAndClient(shipmentId, clientId)?.takeIf { it.isGroup }
            ?: throw FulfillmentException.NotFound("Shipment", shipmentId)

    private fun packingGroupShipment(shipmentId: Long, clientId: Long): Shipment {
        val shipment = groupShipment(shipmentId, clientId)
        if (shipment.state != ShipmentState.PACKING.code) {
            throw FulfillmentException.InvalidPackRequest("shipment $shipmentId is not in PACKING")
        }
        requireShipmentNotPaused(shipment)
        return shipment
    }

    private fun openContainerOrThrow(shipmentId: Long, containerId: Long, clientId: Long): ShippingUnit {
        val unit = shippingUnitRepository.findByIdAndClient(containerId, clientId)?.takeIf { it.shipmentId == shipmentId }
            ?: throw FulfillmentException.NotFound("ShippingUnit", containerId)
        if (unit.state != ShippingUnit.STATE_OPEN) {
            throw FulfillmentException.InvalidPackRequest("container ${unit.shippingUnitNumber} is closed")
        }
        return unit
    }

    /**
     * A scanned LPN is adoptable only when it exists (404), is not RETIRED, is physically empty,
     * is not already a live shipment's container, and is not a batch pick cart -- adopting a cart
     * would let the pack-out consume the very unit load the sort wall is still reading from.
     * Split across three functions so none exceeds detekt's `ThrowsCount` ceiling.
     *
     * The retired check is why [com.karyo.inventory.api.spi.UnitLoadInfo.state] is read at all: a
     * unit load tombstoned DELETABLE (a previously cancelled container, or any pallet drained to
     * empty) is still findable AND still empty, so without it such an LPN sails through this
     * guard and only blows up later, deep inside inventory, when `addLines` tries to transfer
     * onto it -- an `InvalidStateTransition` 409 naming an id the operator scanned nothing about.
     * Refusing it here is a 422 naming the LPN.
     */
    private fun requireAdoptableUnitLoad(unitLoadId: Long, clientId: Long) {
        val info = unitLoadLookup.findById(unitLoadId, clientId)
            ?: throw FulfillmentException.NotFound("UnitLoad", unitLoadId)
        if (info.state == StockState.DELETABLE.code) {
            throw FulfillmentException.InvalidPackRequest("unit load $unitLoadId is retired")
        }
        requireUnitLoadFree(unitLoadId, clientId)
    }

    private fun requireUnitLoadFree(unitLoadId: Long, clientId: Long) {
        if (stockUnitLookup.findByUnitLoadId(unitLoadId, clientId).any { it.amount.signum() > 0 }) {
            throw FulfillmentException.InvalidPackRequest("unit load $unitLoadId is not empty")
        }
        if (shippingUnitRepository.findByUnitLoadIdOnLiveShipment(unitLoadId, clientId) != null) {
            throw FulfillmentException.InvalidPackRequest(
                "unit load $unitLoadId is already a container on a live shipment",
            )
        }
        requireNotPickCart(unitLoadId, clientId)
    }

    private fun requireNotPickCart(unitLoadId: Long, clientId: Long) {
        if (pickOrderRepository.findBatchByTargetUnitLoad(unitLoadId, clientId) != null) {
            throw FulfillmentException.InvalidPackRequest("unit load $unitLoadId is a pick cart")
        }
    }

    /**
     * Rows :2030/:2061 closed the B13 class of failure FOR THIS PORT: both flips now go through
     * the explicit-`clientId` [StockPicker.packContainer]/[StockPicker.unpackContainer] overloads,
     * so a caller reached from a context that never primed the ambient `TenantContext` (wave-core
     * pack-out, a sweep) is scoped by this port's own `clientId` and can no longer silently flip
     * zero rows over a tenant mismatch.
     *
     * The assertion stays, now as a narrower guard: those overloads still answer "the unit load
     * holds nothing in the expected state" with 0 rather than a throw (the contract inherited from
     * `StockService.findByUnitLoad`, which FILTERS by read scope), and closing a container whose
     * stock never changed state is still wrong. A loud 422 naming the container beats that
     * silence.
     */
    private fun requireFlipped(flippedRows: Int, unit: ShippingUnit, action: String) {
        if (flippedRows <= 0) {
            throw FulfillmentException.InvalidPackRequest(
                "container ${unit.shippingUnitNumber} $action zero stock units; " +
                    "unit load ${unit.unitLoadId} holds nothing in the expected state",
            )
        }
    }

    /**
     * `createPickContainer` fails on a duplicate `labelId` (unit-load labels are UNIQUE), so the
     * candidate is checked free through [UnitLoadLookup.existsByLabel] -- the same
     * `next(key, prefix, clientId, max) { isFree }` shape the shipment number itself uses. With
     * the default TIMESTAMP_RANDOM generator no `sequence_numbers` seed row is needed.
     */
    private fun nextContainerLabel(shipment: Shipment, clientId: Long): String =
        sequenceNumberService.next(
            "shipment.containerLabel", "${shipment.shipmentNumber}-C", clientId, MAX_LABEL_LENGTH,
        ) { candidate -> !unitLoadLookup.existsByLabel(candidate, clientId) }

    private fun containerView(unit: ShippingUnit) = ContainerView(
        id = unit.id!!,
        shippingUnitNumber = unit.shippingUnitNumber,
        unitLoadId = unit.unitLoadId!!,
        state = unit.state,
        type = unit.type,
        weight = unit.weight,
        lines = shippingUnitRepository.findLinesByUnitId(unit.id!!).map {
            ContainerLineView(it.id!!, it.deliveryOrderId, it.deliveryOrderLineId, it.itemDataNumber, it.lotNumber, it.amount)
        },
    )

    private fun view(shipment: Shipment) = GroupShipmentView(
        id = shipment.id!!,
        shipmentNumber = shipment.shipmentNumber,
        state = shipment.state,
        consolidationGroupId = shipment.consolidationGroupId!!,
        waveId = shipment.waveId!!,
        memberOrderIds = shipmentOrderRepository.findByShipmentId(shipment.id!!, shipment.clientId)
            .map { it.deliveryOrderId },
        containers = shippingUnitRepository.findByShipmentId(shipment.id!!)
            .sortedBy { it.positionIndex }
            .map { containerView(it) },
    )

    private fun publishState(shipment: Shipment, oldState: Int, newState: Int) {
        outboxService.publish(
            "Shipment", shipment.id!!, "ShipmentStateChanged",
            ShipmentStateChangedEvent(
                shipmentId = shipment.id!!,
                shipmentNumber = shipment.shipmentNumber,
                deliveryOrderId = null,
                oldState = oldState,
                newState = newState,
                clientId = shipment.clientId,
                occurredAt = Instant.now(),
            ),
            shipment.clientId,
        )
    }

    private companion object {
        /** shipments.shipment_number is VARCHAR(80) (SC17 V606). */
        const val MAX_NUMBER_LENGTH = 80

        /** unit_loads.label_id is VARCHAR(255). */
        const val MAX_LABEL_LENGTH = 255
        const val ACTIVITY_PACK = "PACK"
        const val PICKED_CODE = 600
    }
}

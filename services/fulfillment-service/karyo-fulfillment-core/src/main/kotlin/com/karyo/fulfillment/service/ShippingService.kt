package com.karyo.fulfillment.service

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.ShipmentStateChangedEvent
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.spi.ManifestRequest
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.spi.UnitLoadMover
import com.karyo.layout.spi.StagingLocationLookup
import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Shipping (3.4): the two-step ship flow for a PACKED(650) Shipment.
 *
 * 1. [manifest] resolves a carrier via [CarrierAdapterResolver], stamps carrier/tracking on the
 *    Shipment and its ShippingUnits, and advances the Shipment to SHIPPING(670).
 * 2. [dispatch] resolves the SHIP_STAGING dock, flips each shipping-unit container PACKED(650)->
 *    SHIPPED(680) and moves it to the dock, advances the Shipment to SHIPPED(680), and drives the
 *    order to SHIPPED then FINISHED via the orders seam.
 *
 * Every state change writes an outbox event.
 */
@ApplicationScoped
class ShippingService(
    private val shipmentRepository: ShipmentRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val shipmentOrderRepository: ShipmentOrderRepository,
    private val carrierResolver: CarrierAdapterResolver,
    private val stockPicker: StockPicker,
    private val unitLoadMover: UnitLoadMover,
    private val stagingLocationLookup: StagingLocationLookup,
    private val orderProgressionPort: OrderProgressionPort,
    private val outboxService: OutboxService,
    private val tenantContext: TenantContext,
    private val runtimeProperties: RuntimePropertyLookup,
) {

    /**
     * Manifests a PACKED shipment: resolves the carrier + tracking number and stamps them on the
     * shipment and all its shipping units, then advances the shipment to SHIPPING.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun manifest(shipmentId: Long, carrierName: String, carrierService: String, requestedTracking: String?): Shipment {
        val clientId = tenantContext.clientId
        val shipment = shipmentRepository.findByIdAndClient(shipmentId, clientId)
            ?: throw FulfillmentException.NotFound("Shipment", shipmentId)
        if (shipment.state != ShipmentState.PACKED.code) {
            throw FulfillmentException.NotShippable(shipmentId, "shipment is not PACKED")
        }
        requireShipmentNotPaused(shipment)
        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        if (units.isEmpty()) {
            throw FulfillmentException.NotShippable(shipmentId, "shipment has no shipping units")
        }
        val weight = units.fold(BigDecimal.ZERO) { acc, u -> acc + u.weight }
        val assignment = carrierResolver.resolve(
            ManifestRequest(
                shipmentId = shipment.id!!,
                shipmentNumber = shipment.shipmentNumber,
                carrierName = carrierName,
                carrierService = carrierService,
                requestedTracking = requestedTracking,
                weight = weight,
                clientId = clientId,
            ),
        )
        shipment.carrierName = assignment.carrierName
        shipment.carrierService = assignment.carrierService
        shipment.trackingNumber = assignment.trackingNumber
        units.forEach { it.trackingNumber = assignment.trackingNumber }

        val old = shipment.state
        shipment.state = ShipmentState.SHIPPING.code
        publishState(shipment, old, ShipmentState.SHIPPING.code)
        return shipment
    }

    /**
     * Dispatches a SHIPPING shipment: ships + moves each container to the SHIP_STAGING dock,
     * advances the shipment to SHIPPED, and drives the order to SHIPPED then FINISHED.
     *
     * Order matters (changed in defect-burndown-4 task-10): each container is moved to the dock
     * FIRST, then shipped (see the comment inline below for why). One observable consequence:
     * because `StockService.changeState` journals/publishes against the stock unit's CURRENT
     * unit-load location at call time, the resulting SHIP journal row and `StockUnit.StateChanged`
     * outbox event record the SHIP_STAGING dock's location, not the container's pre-dispatch
     * origin. Pinned by `ShippingServiceTest`.
     *
     * S6 (outbound-completion task-8): when [KEY_RENAME_UNIT_LOAD] is true for the shipment's
     * client, each container's `labelId` is renamed (SPI `UnitLoadMover.appendDispatchSuffix`)
     * AFTER the move and BEFORE `shipContainer`, so a container that goes terminal here has its
     * `UnitLoadTrashed` journal/outbox rows carry the NEW label. Pinned by `ShippingServiceTest`
     * and `UnitLoadTerminationTest`.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun dispatch(shipmentId: Long): Shipment {
        val clientId = tenantContext.clientId
        val shipment = shipmentRepository.findByIdAndClient(shipmentId, clientId)
            ?: throw FulfillmentException.NotFound("Shipment", shipmentId)
        if (shipment.state != ShipmentState.SHIPPING.code) {
            throw FulfillmentException.NotShippable(shipmentId, "shipment is not SHIPPING")
        }
        requireShipmentNotPaused(shipment)
        val dock = stagingLocationLookup.findShipStaging(clientId)
            ?: throw FulfillmentException.NotShippable(shipmentId, "no SHIP_STAGING dock configured")

        // Move the container to the dock BEFORE flipping its stock to SHIPPED: a container
        // whose last live stock just shipped can go terminal (UnitLoadTerminator, task-10,
        // defect-burndown-4, row 14), and a DELETABLE unit load refuses to move (StockService/
        // UnitLoadService's DELETABLE-target guard). Moving first also matches the physical
        // sequence -- the pallet is walked to the dock, then handed to the carrier.
        val renameOnDispatch = runtimeProperties.getBoolean(KEY_RENAME_UNIT_LOAD, shipment.clientId, false)
        val units = shippingUnitRepository.findByShipmentId(shipmentId)
        units.mapNotNull { it.unitLoadId }.distinct().forEach { ulId ->
            unitLoadMover.move(ulId, dock.id, dock.name)
            if (renameOnDispatch) {
                unitLoadMover.appendDispatchSuffix(ulId)
            }
            stockPicker.shipContainer(ulId)
        }

        val old = shipment.state
        shipment.state = ShipmentState.SHIPPED.code
        shipment.shippedAt = Instant.now()
        memberOrderIds(shipment).forEach { orderId ->
            orderProgressionPort.markShipped(orderId, clientId)
            orderProgressionPort.markFinished(orderId, clientId)
        }
        publishState(shipment, old, ShipmentState.SHIPPED.code)
        return shipment
    }

    /** Sprint C: a per-order shipment has exactly its one bound order; a GROUP shipment's members live in shipment_orders. */
    private fun memberOrderIds(s: Shipment): List<Long> =
        s.deliveryOrderId?.let { listOf(it) }
            ?: shipmentOrderRepository.findByShipmentId(s.id!!, s.clientId).map { it.deliveryOrderId }

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

    private companion object {
        const val KEY_RENAME_UNIT_LOAD = "karyo.shipping.rename-unit-load"
    }
}

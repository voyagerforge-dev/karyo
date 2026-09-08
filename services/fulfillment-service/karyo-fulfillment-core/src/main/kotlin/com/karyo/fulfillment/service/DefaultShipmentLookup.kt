package com.karyo.fulfillment.service

import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShipmentRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.spi.ShipmentLookup
import com.karyo.fulfillment.vo.ShipmentState
import com.karyo.fulfillment.vo.ShipmentSummary
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process implementation of [ShipmentLookup], scoped to the current tenant like
 * [com.karyo.product.service.DefaultProductLookup]. A delivery order without a shipment is
 * simply absent from the result map -- callers (e.g. orders) treat absence as an honest gap
 * (keep the `--` placeholder), never fabricate carrier/tracking data.
 */
@ApplicationScoped
class DefaultShipmentLookup(
    private val shipmentRepository: ShipmentRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val shipmentOrderRepository: ShipmentOrderRepository,
    private val tenantContext: TenantContext,
) : ShipmentLookup {

    override fun findByDeliveryOrderIds(ids: Set<Long>): Map<Long, ShipmentSummary> {
        if (ids.isEmpty()) return emptyMap()
        val clientId = tenantContext.clientId
        val latestByOrder = shipmentRepository.findByDeliveryOrderIds(ids, clientId)
            .groupBy { it.deliveryOrderId!! }
            .mapValues { (_, shipments) -> latest(shipments) }
        // Sprint C: a GROUP (cross-order) shipment names its members in shipment_orders -- union
        // those orders in too so a wave-consolidated order's summary still resolves.
        val groupByOrder = shipmentOrderRepository.findShipmentIdsByOrderIds(ids, clientId)
        val groupShipments = shipmentRepository.findByIds(groupByOrder.values.toSet(), clientId).associateBy { it.id!! }
        val merged = latestByOrder + groupByOrder.mapNotNull { (orderId, sid) -> groupShipments[sid]?.let { orderId to it } }.toMap()
        // Row 10: fold the shipping-unit-id read into this SAME batched call (never a per-row
        // extra query) so orders-core can derive labelUrl without an N+1.
        val shipmentIds = merged.values.mapNotNull { it.id }.toSet()
        val shippingUnitIds = shippingUnitRepository.findFirstIdsByShipmentIds(shipmentIds, clientId)
        return merged.mapValues { (_, shipment) -> shipment.toSummary(shippingUnitIds[shipment.id]) }
    }

    /** A delivery order should only ever have one shipment; if more than one exists, keep the latest deterministically. */
    private fun latest(shipments: List<Shipment>): Shipment =
        shipments.maxWithOrNull(compareBy({ it.shippedAt ?: it.created }, { it.id ?: 0L }))!!

    private fun Shipment.toSummary(shippingUnitId: Long?) = ShipmentSummary(
        carrierName = carrierName,
        carrierService = carrierService,
        trackingNumber = trackingNumber,
        shippedAt = shippedAt,
        state = state,
        stateName = ShipmentState.fromCode(state).name,
        shippingUnitId = shippingUnitId,
    )
}

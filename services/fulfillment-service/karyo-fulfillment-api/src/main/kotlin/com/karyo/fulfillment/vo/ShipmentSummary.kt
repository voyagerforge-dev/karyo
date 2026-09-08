package com.karyo.fulfillment.vo

import java.time.Instant

/**
 * Cross-module read summary of a [com.karyo.fulfillment.domain.model.Shipment] --
 * the minimal shape other modules (orders) need to show carrier/service/tracking on a
 * delivery order without depending on the fulfillment-core entity. Consumed through the
 * [com.karyo.fulfillment.spi.ShipmentLookup] SPI.
 */
data class ShipmentSummary(
    val carrierName: String?,
    val carrierService: String?,
    val trackingNumber: String?,
    val shippedAt: Instant?,
    val state: Int,
    val stateName: String,
    /**
     * Row 10 (stock-and-orders sprint): id of this shipment's first
     * [com.karyo.fulfillment.domain.model.ShippingUnit] (lowest id), for orders-core to derive
     * `DeliveryOrderResponse.labelUrl` without a per-row extra query. Null when the shipment has
     * no shipping unit yet (packing not started) -- an honest gap, not a fabricated link.
     */
    val shippingUnitId: Long? = null,
)

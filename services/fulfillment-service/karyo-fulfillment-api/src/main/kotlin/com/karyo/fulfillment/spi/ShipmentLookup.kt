package com.karyo.fulfillment.spi

import com.karyo.fulfillment.vo.ShipmentSummary

/**
 * In-process shipment lookup contract. Implemented by fulfillment-core and consumed by
 * other modules (e.g. orders, for the carrier/service/tracking read on a delivery order)
 * instead of a cross-service REST client.
 */
interface ShipmentLookup {
    /** Batch: delivery-order id -> its shipment summary (orders without a shipment are absent). */
    fun findByDeliveryOrderIds(ids: Set<Long>): Map<Long, ShipmentSummary>
}

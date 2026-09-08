package com.karyo.fulfillment.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** Sprint C: member order of a GROUP shipment. Never populated for per-order shipments. */
@Entity
@Table(name = "shipment_orders")
class ShipmentOrder : TenantEntity() {
    @Column(name = "shipment_id", nullable = false) var shipmentId: Long = 0
    @Column(name = "delivery_order_id", nullable = false) var deliveryOrderId: Long = 0
    @Column(name = "delivery_order_number", nullable = false) lateinit var deliveryOrderNumber: String
}

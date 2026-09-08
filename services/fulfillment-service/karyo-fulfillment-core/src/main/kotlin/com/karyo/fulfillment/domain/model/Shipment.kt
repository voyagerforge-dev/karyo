package com.karyo.fulfillment.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "shipments")
class Shipment : TenantEntity() {

    @Column(name = "shipment_number", nullable = false)
    lateinit var shipmentNumber: String

    /** Null on a GROUP (cross-order, Sprint C) shipment -- see [consolidationGroupId]/[isGroup]. */
    @Column(name = "delivery_order_id")
    var deliveryOrderId: Long? = null

    @Column(name = "delivery_order_number")
    var deliveryOrderNumber: String? = null

    @Column(nullable = false)
    var state: Int = 640

    @Column
    var started: Instant? = null

    @Column
    var finished: Instant? = null

    // Dormant shipping fields, populated in 3.4 (shipping).
    @Column(name = "carrier_name")
    var carrierName: String? = null

    @Column(name = "carrier_service")
    var carrierService: String? = null

    @Column(name = "tracking_number")
    var trackingNumber: String? = null

    @Column(name = "shipped_at")
    var shippedAt: Instant? = null

    // Task 5 (outbound-completion sprint): claim/release/pause/resume lifecycle (V608).
    /** Claiming operator -- pure metadata, never coupled to [state] (mirrors GoodsReceipt/PickOrder). */
    @Column(name = "operator_id")
    var operatorId: String? = null

    /** Orthogonal pause stamp; non-null = paused ([state] does not move). */
    @Column(name = "paused_at")
    var pausedAt: Instant? = null

    /** Sprint C: set on a group (cross-order) shipment; members live in shipment_orders. */
    @Column(name = "consolidation_group_id")
    var consolidationGroupId: Long? = null

    @Column(name = "wave_id")
    var waveId: Long? = null

    val isGroup: Boolean get() = consolidationGroupId != null
}

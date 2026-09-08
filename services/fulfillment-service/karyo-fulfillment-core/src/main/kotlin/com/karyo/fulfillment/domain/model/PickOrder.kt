package com.karyo.fulfillment.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "pick_orders")
class PickOrder : TenantEntity() {

    @Column(name = "pick_order_number", nullable = false)
    lateinit var pickOrderNumber: String

    /**
     * V605 (WORKLIST row 20): null marks an EXTINGUISH (stock-clearance) order — myWMS carries
     * no OrderStrategy/DeliveryOrder linkage for these (sprint adjudication 3). This null IS the
     * "EXT marker" [com.karyo.fulfillment.service.PickTopUpService.addPicksToOrder] matches on:
     * an order-bound PickOrder always has a non-null id once V605 is live, so null unambiguously
     * means extinguish.
     */
    @Column(name = "delivery_order_id")
    var deliveryOrderId: Long? = null

    /** Null in lockstep with [deliveryOrderId] — see its KDoc. */
    @Column(name = "delivery_order_number")
    var deliveryOrderNumber: String? = null

    @Column(nullable = false)
    var state: Int = 50

    @Column(nullable = false)
    var prio: Int = 50

    @Column(name = "operator_id", length = 100)
    var operatorId: String? = null

    @Column(name = "target_unit_load_id")
    var targetUnitLoadId: Long? = null

    @Column
    var started: Instant? = null

    @Column
    var finished: Instant? = null

    /**
     * Row 8 (V609): myWMS `PickingOrderGenerator.calculateDestinationLocation` stamps a
     * destination on the generated picking order, not just the delivery order. Resolved once at
     * release time as `order.destinationLocationId ?: strategy.defaultDestinationLocationId` and
     * carried on every PickOrder created from that release (see [PickOrderService.releaseToPicking]).
     * Cross-module id-only reference (the StorageLocation lives in the layout module), no FK.
     */
    @Column(name = "destination_location_id")
    var destinationLocationId: Long? = null

    /** Wave membership (Advanced Fulfillment pack). Null for non-wave pick orders. */
    @Column(name = "wave_id")
    var waveId: Long? = null

    /** Zone key of a cross-order batch pick order. Null for discrete/COMPLETE orders. */
    @Column(name = "batch_zone", length = 50)
    var batchZone: String? = null

    /** Sprint B (V611): BULK-mode batch PickOrder -- aggregated presentation + bulk-confirm fan-out. */
    @Column(nullable = false)
    var bulk: Boolean = false
}

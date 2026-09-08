package com.karyo.fulfillment.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "shipping_unit_lines")
class ShippingUnitLine : TenantEntity() {

    @Column(name = "shipping_unit_id", nullable = false)
    var shippingUnitId: Long = 0

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false)
    lateinit var itemDataNumber: String

    @Column(nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "source_pick_id")
    var sourcePickId: Long? = null

    @Column(name = "lot_number")
    var lotNumber: String? = null

    /** Sprint C: order attribution for cross-order containers; null on legacy (per-order) lines. */
    @Column(name = "delivery_order_id") var deliveryOrderId: Long? = null
    @Column(name = "delivery_order_line_id") var deliveryOrderLineId: Long? = null
}

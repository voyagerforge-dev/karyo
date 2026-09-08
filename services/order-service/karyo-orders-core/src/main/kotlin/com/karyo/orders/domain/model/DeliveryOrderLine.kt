package com.karyo.orders.domain.model

import com.karyo.common.domain.BaseEntity
import com.karyo.orders.vo.OrderState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * One order line. Owned by [DeliveryOrder] (cascade ALL, orphanRemoval).
 * itemDataNumber is denormalized from the product module at creation time
 * (ID-only cross-module reference per ADR-004).
 */
@Entity
@Table(name = "delivery_order_lines")
class DeliveryOrderLine : BaseEntity() {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_order_id")
    lateinit var deliveryOrder: DeliveryOrder

    @Column(name = "line_number", nullable = false)
    var lineNumber: Int = 0

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false, length = 100)
    lateinit var itemDataNumber: String

    @Column(precision = 17, scale = 4, nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "reserved_amount", precision = 17, scale = 4, nullable = false)
    var reservedAmount: BigDecimal = BigDecimal.ZERO

    @Column(nullable = false)
    var state: Int = OrderState.UNDEFINED.code

    @Column(name = "lot_number", length = 255)
    var lotNumber: String? = null

    /**
     * Caller's line reference (e.g. ERP order-line id), D2. Orders-module naming
     * (`externalNumber`, like [DeliveryOrder.externalNumber] / Asn.externalNumber) —
     * NOT inventory's `UnitLoad.externalId`; the divergence is deliberate and recorded.
     */
    @Column(name = "external_number", length = 100)
    var externalNumber: String? = null

    /** Per-unit price at order time; null = honest gap (un-priced line). */
    @Column(name = "unit_price", precision = 15, scale = 2)
    var unitPrice: java.math.BigDecimal? = null

    /** Uncovered remainder; ZERO when fully reserved. */
    val shortage: BigDecimal
        get() = amount.subtract(reservedAmount).max(BigDecimal.ZERO)
}

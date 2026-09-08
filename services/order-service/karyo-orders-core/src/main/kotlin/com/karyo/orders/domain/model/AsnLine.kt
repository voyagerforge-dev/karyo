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
 * One expected ASN line. Owned by [Asn] (cascade ALL, orphanRemoval).
 * itemDataNumber is denormalized from the product module at creation time
 * (ID-only cross-module reference per ADR-004).
 *
 * Line lifecycle: CREATED → STARTED on first receipt → FINISHED when
 * receivedAmount >= expectedAmount or when the ASN is force-finished short.
 */
@Entity
@Table(name = "asn_lines")
class AsnLine : BaseEntity() {

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id")
    lateinit var asn: Asn

    @Column(name = "line_number", nullable = false)
    var lineNumber: Int = 0

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false, length = 100)
    lateinit var itemDataNumber: String

    @Column(name = "expected_amount", precision = 17, scale = 4, nullable = false)
    var expectedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "received_amount", precision = 17, scale = 4, nullable = false)
    var receivedAmount: BigDecimal = BigDecimal.ZERO

    @Column(nullable = false)
    var state: Int = OrderState.UNDEFINED.code

    @Column(name = "lot_number", length = 255)
    var lotNumber: String? = null

    /** Pre-distributed cross-docking target (Advanced Fulfillment); null = not pre-assigned. */
    @Column(name = "cross_dock_delivery_order_id")
    var crossDockDeliveryOrderId: Long? = null

    /** Uncovered remainder; ZERO when fully received (or over-received). */
    val remaining: BigDecimal
        get() = expectedAmount.subtract(receivedAmount).max(BigDecimal.ZERO)
}

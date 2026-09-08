package com.karyo.orders.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * Per-line reservation breakdown: which stock unit holds how much for which line.
 * This exact bookkeeping is what makes cancel/unreserve precise — releasing a line
 * returns exactly the recorded slices to exactly the recorded stock units.
 * stockUnitId is an ID-only cross-module reference into the inventory module.
 */
@Entity
@Table(name = "order_line_reservations")
class OrderLineReservation : BaseEntity() {

    @Column(name = "line_id", nullable = false)
    var lineId: Long = 0

    @Column(name = "stock_unit_id", nullable = false)
    var stockUnitId: Long = 0

    @Column(precision = 17, scale = 4, nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO
}

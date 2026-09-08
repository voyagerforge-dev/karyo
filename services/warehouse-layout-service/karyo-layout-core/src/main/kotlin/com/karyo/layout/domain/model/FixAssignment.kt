package com.karyo.layout.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(
    name = "fix_assignments",
    uniqueConstraints = [UniqueConstraint(columnNames = ["location_id", "item_data_id"])]
)
class FixAssignment : TenantEntity() {
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    lateinit var location: StorageLocation

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", length = 100)
    var itemDataNumber: String? = null

    @Column(name = "min_amount", precision = 17, scale = 4)
    var minAmount: BigDecimal? = null

    @Column(name = "max_amount", precision = 17, scale = 4)
    var maxAmount: BigDecimal? = null

    @Column(name = "desired_amount", precision = 17, scale = 4)
    var desiredAmount: BigDecimal? = null

    /**
     * L7 (locations-layout sprint): myWMS soft per-pick ceiling. When set and less than a
     * single pick's requested amount, inventory-core's StockSelectionService (13-pass FIFO
     * selection) skips this slot for that request rather than partially draining it — see
     * that class's KDoc for the full myWMS-fidelity rationale. Does NOT cap total stock at
     * the location.
     */
    @Column(name = "max_pick_amount", precision = 17, scale = 4)
    var maxPickAmount: BigDecimal? = null

    @Column(name = "order_index")
    var orderIndex: Int = 0
}

package com.karyo.product.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "packaging_units")
class PackagingUnit : BaseEntity() {
    @Column(nullable = false, length = 100)
    lateinit var name: String

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "item_data_id")
    lateinit var itemData: ItemData

    @Column(nullable = false, precision = 17, scale = 4)
    var amount: BigDecimal = BigDecimal.ONE

    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "item_unit_id")
    var itemUnit: ItemUnit? = null

    @Column(precision = 16, scale = 3)
    var height: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var width: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var depth: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var weight: BigDecimal? = null

    /**
     * Ordinal nesting rank of this packaging unit within its product's packaging hierarchy
     * (myWMS parity). Convention, not enforced: 0 = base/each, 1 = carton, 2 = layer,
     * 3 = pallet. Orders a SKU's packaging units from innermost to outermost.
     */
    @Column(name = "packing_level", nullable = false)
    var packingLevel: Int = 0
}

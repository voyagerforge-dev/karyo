package com.karyo.product.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "item_data_numbers")
class ItemDataNumber : BaseEntity() {
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "item_data_id")
    lateinit var itemData: ItemData

    @Column(nullable = false, length = 100)
    lateinit var number: String

    @Column(name = "number_type", length = 30)
    var numberType: String? = null

    @Column(name = "packaging_unit_id")
    var packagingUnitId: Long? = null

    /** Who manufactures under this number (myWMS parity, C3). Free text, no validation beyond length. */
    @Column(name = "manufacturer_name", length = 255)
    var manufacturerName: String? = null

    @Column(name = "index", nullable = false)
    var index: Int = 0
}

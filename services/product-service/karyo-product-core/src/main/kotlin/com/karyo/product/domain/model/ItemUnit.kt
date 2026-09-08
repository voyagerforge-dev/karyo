package com.karyo.product.domain.model

import com.karyo.common.domain.BaseEntity
import com.karyo.product.vo.ItemUnitType
import jakarta.persistence.*

@Entity
@Table(name = "item_units")
class ItemUnit : BaseEntity() {
    @Column(nullable = false, unique = true, length = 20)
    lateinit var name: String

    @Column(name = "unit_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var unitType: ItemUnitType = ItemUnitType.PIECE
}

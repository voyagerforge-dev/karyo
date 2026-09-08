package com.karyo.product.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** A 1:1 substitution: when [itemDataId] is short, [substituteItemDataId] may be picked instead. */
@Entity
@Table(name = "item_substitutions")
class ItemSubstitution : TenantEntity() {

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "substitute_item_data_id", nullable = false)
    var substituteItemDataId: Long = 0

    @Column(nullable = false)
    var priority: Int = 1

    @Column(nullable = false)
    var active: Boolean = true
}

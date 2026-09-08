package com.karyo.inventory.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "unit_load_types")
class UnitLoadType : BaseEntity() {
    @Column(nullable = false, unique = true, length = 100)
    lateinit var name: String

    @Column(precision = 16, scale = 3)
    var height: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var width: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var depth: BigDecimal? = null

    @Column(name = "lifting_capacity", precision = 16, scale = 3)
    var liftingCapacity: BigDecimal? = null

    @Column(precision = 16, scale = 3)
    var weight: BigDecimal? = null

    @Column(length = 255)
    var usages: String? = null

    @Column(name = "aggregate_stocks", nullable = false)
    var aggregateStocks: Boolean = false

    /**
     * Row 16: when true, an emptied unit load of this type is KEPT instead of being auto-trashed
     * by [com.karyo.inventory.service.UnitLoadTerminator.trashIfEmpty]. This is the
     * reusable-container case: a tote or pallet that goes back into circulation rather than out
     * of the system. Behavioral parity with the myWMS UnitLoadType flag of the same name.
     */
    @Column(name = "manage_empties", nullable = false)
    var manageEmpties: Boolean = false

    fun hasUsage(usage: String): Boolean =
        usages?.split(",")?.map { it.trim() }?.contains(usage) == true
}

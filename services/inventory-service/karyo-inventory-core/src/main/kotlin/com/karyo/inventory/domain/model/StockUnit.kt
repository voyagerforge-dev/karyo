package com.karyo.inventory.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "stock_units")
class StockUnit : TenantEntity() {
    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false, length = 100)
    lateinit var itemDataNumber: String

    @Column(precision = 17, scale = 4, nullable = false)
    var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "reserved_amount", precision = 17, scale = 4, nullable = false)
    var reservedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "serial_number", length = 255)
    var serialNumber: String? = null

    @Column(name = "lot_number", length = 255)
    var lotNumber: String? = null

    @Column(name = "best_before")
    var bestBefore: LocalDate? = null

    @Column(nullable = false)
    var state: Int = StockState.UNDEFINED.code

    @Column(name = "lock_type", nullable = false)
    var lockType: Int = LockType.UNLOCKED.code

    @Column(name = "strategy_date")
    var strategyDate: Instant? = null

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_load_id")
    lateinit var unitLoad: UnitLoad

    @Column(name = "packaging_unit_id")
    var packagingUnitId: Long? = null

    @Column(name = "activity_code", length = 50)
    var activityCode: String? = null

    val availableAmount: BigDecimal
        get() = amount.subtract(reservedAmount)
}

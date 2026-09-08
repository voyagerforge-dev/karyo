package com.karyo.stocktaking.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.stocktaking.vo.CountLineState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "count_lines")
class CountLine : TenantEntity() {

    @Column(name = "count_order_id", nullable = false)
    var countOrderId: Long = 0

    @Column(name = "stock_unit_id", nullable = false)
    var stockUnitId: Long = 0

    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "item_data_number", nullable = false, length = 100)
    lateinit var itemDataNumber: String

    @Column(name = "lot_number")
    var lotNumber: String? = null

    @Column(name = "serial_number")
    var serialNumber: String? = null

    @Column(name = "planned_amount", nullable = false)
    var plannedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "counted_amount")
    var countedAmount: BigDecimal? = null

    @Column(nullable = false)
    var state: Int = CountLineState.PLANNED.code

    /** St4 (V704) -- snapshotted at [com.karyo.stocktaking.service.StocktakingService.generateOrderForLocation]
     *  time from [com.karyo.inventory.api.spi.CountableStock]. Nullable: pre-migration rows and
     *  demo-generated rows have no unit-load context (frontend groups a null [unitLoadLabel]
     *  under "Loose stock"). */
    @Column(name = "unit_load_id")
    var unitLoadId: Long? = null

    @Column(name = "unit_load_label")
    var unitLoadLabel: String? = null
}

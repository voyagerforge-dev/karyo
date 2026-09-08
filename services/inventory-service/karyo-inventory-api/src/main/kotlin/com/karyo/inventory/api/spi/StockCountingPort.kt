package com.karyo.inventory.api.spi

import java.math.BigDecimal

/** Count-specific stock operations. Tenant-scoped via the request's TenantContext. */
interface StockCountingPort {
    fun findStockAtLocation(locationId: Long): List<CountableStock>
    fun lockForCount(stockUnitIds: List<Long>)
    fun releaseCount(stockUnitIds: List<Long>)
    fun applyCount(stockUnitId: Long, countedAmount: BigDecimal, activityCode: String)
    fun recordMatchCounted(stockUnitId: Long, activityCode: String)
}

data class CountableStock(
    val stockUnitId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val serialNumber: String?,
    val amount: BigDecimal,
    val reservedAmount: BigDecimal,
    /** St4: the owning unit load -- every StockUnit has one (non-nullable on the entity),
     *  so this port always populates both. */
    val unitLoadId: Long,
    val unitLoadLabel: String,
)

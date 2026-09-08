package com.karyo.inventory.api.vo

import java.math.BigDecimal

data class StockSelectionRequest(
    val itemDataId: Long,
    val amount: BigDecimal,
    val clientId: Long,
    val lotNumber: String? = null,
    val enforceLot: Boolean = false,
    val useLockedStock: Boolean = false,
    val preferComplete: Boolean = true,
    val preferMatching: Boolean = false,
    val completeHandling: Int = 0,
    val excludeStockUnitIds: List<Long> = emptyList(),
)

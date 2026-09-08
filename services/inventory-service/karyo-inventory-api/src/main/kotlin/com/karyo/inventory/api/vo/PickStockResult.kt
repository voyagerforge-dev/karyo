package com.karyo.inventory.api.vo

import java.math.BigDecimal

data class PickStockResult(
    val stockUnitId: Long,
    val unitLoadId: Long,
    val unitLoadLabel: String,
    val locationId: Long,
    val locationName: String,
    val availableAmount: BigDecimal,
    val suggestedPickAmount: BigDecimal?,
    val pickingType: PickingType,
)

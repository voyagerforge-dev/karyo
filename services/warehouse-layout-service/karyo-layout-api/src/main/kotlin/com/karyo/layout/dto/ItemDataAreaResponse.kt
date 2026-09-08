package com.karyo.layout.dto

import java.math.BigDecimal

data class ItemDataAreaResponse(
    val id: Long,
    val itemDataId: Long,
    val itemDataName: String?,
    val storageAreaId: Long,
    val storageAreaName: String,
    val plannedAmount: BigDecimal?,
    val plannedStocks: Int?,
    val created: String,
    val modified: String,
)

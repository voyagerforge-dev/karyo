package com.karyo.layout.dto

import java.math.BigDecimal

data class UpdateItemDataAreaRequest(
    val plannedAmount: BigDecimal? = null,
    val plannedStocks: Int? = null,
)

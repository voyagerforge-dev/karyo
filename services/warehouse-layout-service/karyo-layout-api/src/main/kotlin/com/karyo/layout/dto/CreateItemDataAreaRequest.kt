package com.karyo.layout.dto

import jakarta.validation.constraints.*
import java.math.BigDecimal

data class CreateItemDataAreaRequest(
    @field:NotNull val itemDataId: Long,
    @field:NotNull val storageAreaId: Long,
    val plannedAmount: BigDecimal? = null,
    val plannedStocks: Int? = null,
)

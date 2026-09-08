package com.karyo.product.dto

import java.math.BigDecimal

data class PackagingUnitResponse(
    val id: Long,
    val name: String,
    val amount: BigDecimal,
    val itemUnitName: String?,
    val weight: BigDecimal?,
    val height: BigDecimal?,
    val width: BigDecimal?,
    val depth: BigDecimal?,
    val packingLevel: Int = 0,
)

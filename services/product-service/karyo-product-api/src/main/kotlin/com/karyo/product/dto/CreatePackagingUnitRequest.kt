package com.karyo.product.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class CreatePackagingUnitRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotNull @field:DecimalMin("0.0001") val amount: BigDecimal,
    val itemUnitId: Long? = null,
    val height: BigDecimal? = null,
    val width: BigDecimal? = null,
    val depth: BigDecimal? = null,
    val weight: BigDecimal? = null,
    @field:Min(0) val packingLevel: Int = 0,
)

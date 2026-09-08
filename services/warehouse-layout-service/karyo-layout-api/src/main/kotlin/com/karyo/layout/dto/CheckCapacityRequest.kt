package com.karyo.layout.dto

import jakarta.validation.constraints.*
import java.math.BigDecimal

data class CheckCapacityRequest(
    @field:NotNull @field:DecimalMin("0") val proposedWeight: BigDecimal,
)

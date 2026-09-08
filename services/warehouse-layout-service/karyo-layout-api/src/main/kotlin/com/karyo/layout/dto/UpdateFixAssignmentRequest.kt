package com.karyo.layout.dto

import jakarta.validation.constraints.DecimalMin
import java.math.BigDecimal

data class UpdateFixAssignmentRequest(
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val desiredAmount: BigDecimal? = null,
    // L7: soft per-pick ceiling — validated > 0 when present (same rule as create).
    @field:DecimalMin(value = "0", inclusive = false)
    val maxPickAmount: BigDecimal? = null,
    val orderIndex: Int? = null,
)

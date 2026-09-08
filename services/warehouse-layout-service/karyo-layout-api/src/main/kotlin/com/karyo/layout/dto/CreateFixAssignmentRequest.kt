package com.karyo.layout.dto

import jakarta.validation.constraints.*
import java.math.BigDecimal

data class CreateFixAssignmentRequest(
    @field:NotNull val locationId: Long,
    @field:NotNull val itemDataId: Long,
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val desiredAmount: BigDecimal? = null,
    // L7: soft per-pick ceiling (myWMS FixAssignment.maxPickAmount) — optional (null = unbounded,
    // today's behavior); validated > 0 when present, mirroring CreateTypeCapacityConstraintRequest.allocation.
    @field:DecimalMin(value = "0", inclusive = false)
    val maxPickAmount: BigDecimal? = null,
    val orderIndex: Int = 0,
)

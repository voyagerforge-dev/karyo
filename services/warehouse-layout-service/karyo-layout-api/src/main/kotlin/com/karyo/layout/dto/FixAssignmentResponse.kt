package com.karyo.layout.dto

import java.math.BigDecimal

data class FixAssignmentResponse(
    val id: Long,
    val locationId: Long,
    val locationName: String,
    val itemDataId: Long,
    val itemDataNumber: String?,
    val minAmount: BigDecimal?,
    val maxAmount: BigDecimal?,
    val desiredAmount: BigDecimal?,
    val maxPickAmount: BigDecimal?,
    val currentStockAmount: BigDecimal?,
    val orderIndex: Int,
    val created: String,
    val modified: String,
)

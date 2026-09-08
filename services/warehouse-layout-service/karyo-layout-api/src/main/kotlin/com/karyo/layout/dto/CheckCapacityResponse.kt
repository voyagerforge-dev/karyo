package com.karyo.layout.dto

import java.math.BigDecimal

data class CheckCapacityResponse(
    val allowed: Boolean,
    val currentWeight: BigDecimal,
    val proposedWeight: BigDecimal,
    val liftingCapacity: BigDecimal?,
    val reason: String?,
)

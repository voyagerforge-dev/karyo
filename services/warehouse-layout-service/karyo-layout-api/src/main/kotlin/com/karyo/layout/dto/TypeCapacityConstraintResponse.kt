package com.karyo.layout.dto

import java.math.BigDecimal

data class TypeCapacityConstraintResponse(
    val id: Long,
    val locationTypeId: Long,
    val locationTypeName: String,
    val unitLoadTypeId: Long,
    val allocation: BigDecimal,
    val orderIndex: Int,
    val created: String,
    val modified: String,
)

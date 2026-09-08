package com.karyo.product.dto

data class ItemDataNumberResponse(
    val id: Long,
    val number: String,
    val numberType: String?,
    val packagingUnitId: Long?,
    val manufacturerName: String? = null,
)

package com.karyo.product.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateItemDataNumberRequest(
    @field:NotBlank @field:Size(max = 100) val number: String,
    @field:Size(max = 30) val numberType: String? = null,
    val packagingUnitId: Long? = null,
    @field:Size(max = 255) val manufacturerName: String? = null,
)

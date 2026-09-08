package com.karyo.product.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateItemUnitRequest(
    @field:NotBlank @field:Size(max = 20) val name: String,
    val unitType: String = "PIECE",
)

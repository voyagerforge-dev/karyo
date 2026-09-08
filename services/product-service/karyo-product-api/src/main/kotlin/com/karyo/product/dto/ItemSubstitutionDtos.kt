package com.karyo.product.dto

import jakarta.validation.constraints.NotNull

data class CreateItemSubstitutionRequest(
    @field:NotNull val itemDataId: Long,
    @field:NotNull val substituteItemDataId: Long,
    val priority: Int = 1,
    val active: Boolean = true,
)

data class ItemSubstitutionResponse(
    val id: Long,
    val itemDataId: Long,
    val substituteItemDataId: Long,
    val priority: Int,
    val active: Boolean,
)

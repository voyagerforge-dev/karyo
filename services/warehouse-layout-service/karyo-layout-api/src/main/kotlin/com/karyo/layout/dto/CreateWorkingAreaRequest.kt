package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateWorkingAreaRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    val clusterIds: List<Long> = emptyList(),
)

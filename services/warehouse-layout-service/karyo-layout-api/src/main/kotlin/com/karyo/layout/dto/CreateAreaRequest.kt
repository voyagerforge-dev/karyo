package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateAreaRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val usages: List<String> = emptyList(),
)

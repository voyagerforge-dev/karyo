package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateZoneRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 500) val description: String? = null,
    val overflowZoneId: Long? = null,
)

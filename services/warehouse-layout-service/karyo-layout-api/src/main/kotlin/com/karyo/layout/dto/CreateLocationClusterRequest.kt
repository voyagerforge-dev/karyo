package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateLocationClusterRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val parentClusterId: Long? = null,
)

package com.karyo.layout.dto

import jakarta.validation.constraints.NotNull

data class LockLocationRequest(
    @field:NotNull val lockType: Int,
    val reason: String? = null,
)

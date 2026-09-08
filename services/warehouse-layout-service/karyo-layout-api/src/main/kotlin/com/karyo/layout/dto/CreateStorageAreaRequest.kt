package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateStorageAreaRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    val clusterIds: List<Long> = emptyList(),
    /** PT15: marks every location in this area as a transfer-staging waypoint. */
    val transferStaging: Boolean = false,
)

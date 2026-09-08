package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateStorageStrategyRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val zoneId: Long? = null,
    val mixItem: Boolean = true,
    val mixClient: Boolean = false,
    val nearPickingLocation: Boolean = false,
    @field:Size(max = 255) val sorts: String? = null,
    // Row 17 (defect-burndown-4, Task 11): defaults true -- owner-scoped putaway unless a
    // strategy explicitly opts into reaching shared (client_id=0) locations. See
    // StorageStrategy.onlyClientLocation for the full rationale.
    val onlyClientLocation: Boolean = true,
    val manualSearch: Boolean = false,
    val useAreaStrategyDate: Boolean = false,
    val useItemDataArea: Boolean = false,
)

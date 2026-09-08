package com.karyo.layout.dto

import jakarta.validation.constraints.*

data class CreateLocationRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 100) val scanCode: String? = null,
    @field:NotNull val locationTypeId: Long,
    @field:NotNull val areaId: Long,
    val zoneId: Long? = null,
    val locationClusterId: Long? = null,
    val orderIndex: Int = 0,
    val xPos: Int = 0,
    val yPos: Int = 0,
    val zPos: Int = 0,
    @field:Size(max = 50) val rack: String? = null,
    @field:Size(max = 50) val field: String? = null,
    @field:Size(max = 50) val section: String? = null,
    /** L3: free-text automation-system (PLC/WCS) address — search/display only. */
    @field:Size(max = 64) val plcCode: String? = null,
    /** L3: 0 = normal/searchable; non-zero excludes the location from the putaway finder. */
    val allocationState: Int = 0,
    /** Task 10 (locations-layout sprint): V309 seeder-derived metadata, now settable at create time. */
    val capacity: Int? = null,
    @field:Size(max = 20) val temperatureZone: String? = null,
    @field:Size(max = 20) val handlingClass: String? = null,
    @field:Size(max = 20) val kind: String? = null,
    /** Task 10: A2-1's isClearing was update-only; the create form needs it too (e.g. seeding
     * a brand-new clearing location without a follow-up PUT). Same singleton enforcement. */
    val isClearing: Boolean = false,
)

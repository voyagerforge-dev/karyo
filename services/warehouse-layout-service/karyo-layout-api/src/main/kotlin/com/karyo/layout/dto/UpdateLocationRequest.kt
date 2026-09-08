package com.karyo.layout.dto

import jakarta.validation.constraints.Size

data class UpdateLocationRequest(
    @field:Size(max = 100) val scanCode: String? = null,
    val locationTypeId: Long? = null,
    val areaId: Long? = null,
    val zoneId: Long? = null,
    val locationClusterId: Long? = null,
    val orderIndex: Int? = null,
    val xPos: Int? = null,
    val yPos: Int? = null,
    val zPos: Int? = null,
    @field:Size(max = 50) val rack: String? = null,
    @field:Size(max = 50) val field: String? = null,
    @field:Size(max = 50) val section: String? = null,
    /** A2-1: null means unchanged (same convention as every other field on this DTO). */
    val isClearing: Boolean? = null,
    /** L3: null means unchanged. Free-text automation-system (PLC/WCS) address. */
    @field:Size(max = 64) val plcCode: String? = null,
    /** L3: null means unchanged. 0 = normal/searchable; non-zero excludes from the putaway finder. */
    val allocationState: Int? = null,
    /** Task 10 (locations-layout sprint): V309 seeder-derived metadata was CreateOnly/response-only
     * until now — the edit form needs to be able to set/change it. Null means unchanged. */
    val capacity: Int? = null,
    @field:Size(max = 20) val temperatureZone: String? = null,
    @field:Size(max = 20) val handlingClass: String? = null,
    @field:Size(max = 20) val kind: String? = null,
)

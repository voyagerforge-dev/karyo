package com.karyo.reporting.api.v1.dto

/** A single location cell. state ∈ "occupied" | "empty" | "locked". */
data class OccupancyCell(
    val id: Long,
    val name: String,
    val state: String,
    val capacity: Int? = null,
    val unitLoadCount: Int = 0,
)

data class OccupancyZone(
    val zoneId: Long?,
    val zoneName: String,
    val occupied: Int,
    val total: Int,
    val pct: Double,
    val locations: List<OccupancyCell>,
    /** Σ capacity over locations in this zone that have a non-null capacity; null if none do. */
    val capacitySlots: Int? = null,
    /** Σ unit_load_count over those same capacitied locations; null if none do. */
    val usedSlots: Int? = null,
)

data class OccupancyTotals(
    val occupied: Int,
    val total: Int,
    val pct: Double,
    /** Σ capacity over locations with a non-null capacity. */
    val capacitySlots: Int = 0,
    /** Σ unit_load_count over those same capacitied locations. */
    val usedSlots: Int = 0,
    /** Count of locations that carry a non-null capacity. */
    val locationsWithCapacity: Int = 0,
    /** usedSlots / capacitySlots; null when capacitySlots is 0 (no location has capacity set). */
    val utilization: Double? = null,
)

data class OccupancyResponse(
    val zones: List<OccupancyZone>,
    val unzoned: OccupancyZone?,
    val totals: OccupancyTotals,
)

package com.karyo.reporting.service

import com.karyo.reporting.api.v1.dto.*
import com.karyo.reporting.repository.LocationOccupancyRow
import com.karyo.reporting.repository.OccupancyViewRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class OccupancyService(private val repo: OccupancyViewRepository) {

    fun build(clientId: Long): OccupancyResponse {
        val rows = repo.all(clientId)

        // group preserving the repo's ORDER BY (zone_name, order_index, name)
        val zoned = rows.filter { it.zoneId != null }
            .groupBy { it.zoneId!! to (it.zoneName ?: "") }
            .map { (key, rs) -> zone(key.second, key.first, rs) }
            .sortedBy { it.zoneName }

        val unzonedRows = rows.filter { it.zoneId == null }
        val unzoned = if (unzonedRows.isEmpty()) null else zone("Unzoned", null, unzonedRows)

        val occ = rows.count { it.occupied }
        val total = rows.size
        val capacitied = rows.filter { it.capacity != null }
        val capacitySlots = capacitied.sumOf { it.capacity!! }
        val usedSlots = capacitied.sumOf { it.unitLoadCount }
        val utilization = if (capacitySlots == 0) null else usedSlots.toDouble() / capacitySlots
        return OccupancyResponse(
            zoned,
            unzoned,
            OccupancyTotals(occ, total, pct(occ, total), capacitySlots, usedSlots, capacitied.size, utilization),
        )
    }

    private fun zone(name: String, id: Long?, rs: List<LocationOccupancyRow>): OccupancyZone {
        val occ = rs.count { it.occupied }
        val cells = rs.map { OccupancyCell(it.locationId, it.locationName, state(it), it.capacity, it.unitLoadCount) }
        val capacitied = rs.filter { it.capacity != null }
        val capacitySlots = if (capacitied.isEmpty()) null else capacitied.sumOf { it.capacity!! }
        val usedSlots = if (capacitied.isEmpty()) null else capacitied.sumOf { it.unitLoadCount }
        return OccupancyZone(id, name, occ, rs.size, pct(occ, rs.size), cells, capacitySlots, usedSlots)
    }

    /** locked takes display precedence over occupied. */
    private fun state(r: LocationOccupancyRow): String = when {
        r.locked -> "locked"
        r.occupied -> "occupied"
        else -> "empty"
    }

    private fun pct(occ: Int, total: Int): Double = if (total == 0) 0.0 else occ.toDouble() / total
}

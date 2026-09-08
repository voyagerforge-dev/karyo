package com.karyo.reporting.repository

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager

data class LocationOccupancyRow(
    val locationId: Long,
    val locationName: String,
    val zoneId: Long?,
    val zoneName: String?,
    val orderIndex: Int,
    val occupied: Boolean,
    val locked: Boolean,
    val capacity: Int?,
    val unitLoadCount: Int,
)

@ApplicationScoped
class OccupancyViewRepository(private val em: EntityManager) {

    private fun long(v: Any?): Long = (v as Number).toLong()
    private fun longOrNull(v: Any?): Long? = (v as Number?)?.toLong()
    private fun int(v: Any?): Int = (v as Number).toInt()
    private fun intOrNull(v: Any?): Int? = (v as Number?)?.toInt()
    private fun bool(v: Any?): Boolean = v as Boolean

    @Suppress("UNCHECKED_CAST")
    fun all(clientId: Long): List<LocationOccupancyRow> =
        (em.createNativeQuery(
            "SELECT location_id, location_name, zone_id, zone_name, order_index, occupied, locked, " +
            "capacity, unit_load_count " +
            "FROM karyo.kpi_location_occupancy WHERE client_id = ?1 " +
            "ORDER BY zone_name NULLS LAST, order_index, location_name"
        ).setParameter(1, clientId).resultList as List<Array<Any?>>)
            .map {
                LocationOccupancyRow(
                    locationId = long(it[0]),
                    locationName = it[1] as String,
                    zoneId = longOrNull(it[2]),
                    zoneName = it[3] as String?,
                    orderIndex = int(it[4]),
                    occupied = bool(it[5]),
                    locked = bool(it[6]),
                    capacity = intOrNull(it[7]),
                    unitLoadCount = int(it[8]),
                )
            }
}

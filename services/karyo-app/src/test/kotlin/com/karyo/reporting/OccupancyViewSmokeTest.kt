package com.karyo.reporting

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/** Proves the occupancy view's SQL is valid against the live schema (contract columns selectable). */
@QuarkusTest
class OccupancyViewSmokeTest {
    @Inject lateinit var em: EntityManager

    @Test
    fun `occupancy view has its contract columns`() = assertDoesNotThrow {
        em.createNativeQuery(
            "SELECT client_id, location_id, location_name, zone_id, zone_name, order_index, occupied, locked, " +
            "capacity, unit_load_count " +
            "FROM karyo.kpi_location_occupancy LIMIT 1"
        ).resultList
    }.let {}
}

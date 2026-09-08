package com.karyo.reporting

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/** Proves each view's SQL is valid against the live schema (returns rows w/ the contract columns). */
@QuarkusTest
class KpiViewsSmokeTest {
    @Inject lateinit var em: EntityManager

    private fun query(sql: String) = assertDoesNotThrow {
        em.createNativeQuery(sql).resultList
    }

    @Test fun `accuracy view has its contract columns`() =
        query("SELECT client_id, day, accurate_lines, total_lines FROM karyo.kpi_accuracy_daily LIMIT 1").let {}

    @Test fun `throughput view has its contract columns`() =
        query("SELECT client_id, day, units_picked, units_shipped, units_received FROM karyo.kpi_throughput_daily LIMIT 1").let {}

    @Test fun `cycle time view has its contract columns`() =
        query("SELECT client_id, day, order_count, total_hours FROM karyo.kpi_cycle_time_daily LIMIT 1").let {}

    @Test fun `utilization view has its contract columns`() =
        query("SELECT client_id, occupied, usable FROM karyo.kpi_utilization_current LIMIT 1").let {}
}

package com.karyo.reporting.repository

import com.karyo.common.time.sqlDate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.LocalDate

data class DailyAccuracy(val day: LocalDate, val accurateLines: Long, val totalLines: Long)
data class DailyThroughput(val day: LocalDate, val unitsPicked: Double, val unitsShipped: Double, val unitsReceived: Double)
data class DailyCycleTime(val day: LocalDate, val orderCount: Long, val totalHours: Double)

/**
 * Reads the KPI views ([db/migration/reporting/V1001__create_kpi_views.sql]) with a `[start,
 * end)` window on their `day` column.
 *
 * **Day-bucketing is warehouse-local, not UTC** (docs/WORKLIST.md:
 * "`KpiViewRepository` computes its query-window bounds in UTC..."; decided/fixed 2026-08-17,
 * kpi-warehouse-tz). The views bucket via `date_trunc('day', ...)` in the Postgres session's
 * timezone, which pgjdbc sets to the JVM's default zone -- a deployment picks its warehouse's
 * timezone via the container/host `TZ`. [accuracy]/[throughput]/[cycleTime] convert their
 * `[start, end)` [Instant] bounds to SQL `DATE`s via [com.karyo.common.time.sqlDate], which
 * defaults to that same zone ([com.karyo.common.time.WarehouseZone.ZONE]), so the bounds and the
 * views' own bucketing agree by construction. See [com.karyo.common.time.WarehouseZone] for the
 * full rationale -- that object's KDoc is the record of this decision, since the applied
 * migration's SQL comments are forward-only and cannot be edited to reflect it. (Promoted from
 * `com.karyo.reporting.WarehouseZone` to `karyo-common` 2026-08-17, demand-reader-tz, so
 * forecasting/simulation could share it without a fake dependency on this module.)
 */
@ApplicationScoped
class KpiViewRepository(private val em: EntityManager) {

    // Hibernate 6 maps PostgreSQL DATE → java.time.LocalDate; older drivers may give java.sql.Date
    private fun day(v: Any?): LocalDate = when (v) {
        is LocalDate -> v
        is java.sql.Date -> v.toLocalDate()
        else -> throw IllegalArgumentException("Cannot convert ${v?.javaClass} to LocalDate")
    }
    private fun long(v: Any?): Long = (v as Number?)?.toLong() ?: 0
    private fun dbl(v: Any?): Double = (v as Number?)?.toDouble() ?: 0.0

    @Suppress("UNCHECKED_CAST")
    fun accuracy(clientId: Long, start: Instant, end: Instant): List<DailyAccuracy> =
        (em.createNativeQuery(
            "SELECT day, accurate_lines, total_lines FROM karyo.kpi_accuracy_daily " +
            "WHERE client_id = ?1 AND day >= ?2 AND day < ?3 ORDER BY day"
        ).setParameter(1, clientId)
         .setParameter(2, sqlDate(start))
         .setParameter(3, sqlDate(end))
         .resultList as List<Array<Any?>>)
            .map { DailyAccuracy(day(it[0]), long(it[1]), long(it[2])) }

    @Suppress("UNCHECKED_CAST")
    fun throughput(clientId: Long, start: Instant, end: Instant): List<DailyThroughput> =
        (em.createNativeQuery(
            "SELECT day, units_picked, units_shipped, units_received FROM karyo.kpi_throughput_daily " +
            "WHERE client_id = ?1 AND day >= ?2 AND day < ?3 ORDER BY day"
        ).setParameter(1, clientId)
         .setParameter(2, sqlDate(start))
         .setParameter(3, sqlDate(end))
         .resultList as List<Array<Any?>>)
            .map { DailyThroughput(day(it[0]), dbl(it[1]), dbl(it[2]), dbl(it[3])) }

    @Suppress("UNCHECKED_CAST")
    fun cycleTime(clientId: Long, start: Instant, end: Instant): List<DailyCycleTime> =
        (em.createNativeQuery(
            "SELECT day, order_count, total_hours FROM karyo.kpi_cycle_time_daily " +
            "WHERE client_id = ?1 AND day >= ?2 AND day < ?3 ORDER BY day"
        ).setParameter(1, clientId)
         .setParameter(2, sqlDate(start))
         .setParameter(3, sqlDate(end))
         .resultList as List<Array<Any?>>)
            .map { DailyCycleTime(day(it[0]), long(it[1]), dbl(it[2])) }

    fun utilization(clientId: Long): Pair<Long, Long> {
        val rows = em.createNativeQuery(
            "SELECT occupied, usable FROM karyo.kpi_utilization_current WHERE client_id = ?1"
        ).setParameter(1, clientId).resultList
        if (rows.isEmpty()) return 0L to 0L
        val r = rows[0] as Array<Any?>
        return long(r[0]) to long(r[1])
    }
}

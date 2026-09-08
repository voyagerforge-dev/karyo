package com.karyo.reporting.service

import com.karyo.common.time.WarehouseZone
import java.time.Duration
import java.time.Instant

/**
 * `D7`/`D30`/`D90` are pure durations (no calendar-day boundary), so they need no timezone.
 * `YTD`'s "start of this calendar year" is a day-boundary computation and must use the same
 * zone the KPI views bucket days in -- [WarehouseZone.ZONE] -- so a Jan-1-warehouse-local row
 * lands inside the YTD window even when UTC hasn't rolled to Jan 1 yet (or has already rolled
 * past Dec 31 warehouse-local). See [WarehouseZone] (`karyo-common`) for the full
 * warehouse-local day-bucketing decision (docs/WORKLIST.md: "`KpiViewRepository` computes its
 * query-window bounds in UTC..."; decided/fixed 2026-08-17, kpi-warehouse-tz).
 */
enum class KpiRange(val code: String, val label: String) {
    D7("7D", "Last 7 days"),
    D30("30D", "Last 30 days"),
    D90("90D", "Last 90 days"),
    YTD("YTD", "Year to date");

    /** [start, end) for this range ending at `now`. */
    fun window(now: Instant): Pair<Instant, Instant> = when (this) {
        D7 -> now.minus(Duration.ofDays(7)) to now
        D30 -> now.minus(Duration.ofDays(30)) to now
        D90 -> now.minus(Duration.ofDays(90)) to now
        YTD -> now.atZone(WarehouseZone.ZONE).toLocalDate().withDayOfYear(1)
            .atStartOfDay(WarehouseZone.ZONE).toInstant() to now
    }

    /** The equal-length window immediately preceding this one (for delta). */
    fun priorWindow(now: Instant): Pair<Instant, Instant> {
        val (start, end) = window(now)
        val len = Duration.between(start, end)
        return start.minus(len) to start
    }

    companion object {
        fun from(code: String): KpiRange? = entries.firstOrNull { it.code == code }
    }
}

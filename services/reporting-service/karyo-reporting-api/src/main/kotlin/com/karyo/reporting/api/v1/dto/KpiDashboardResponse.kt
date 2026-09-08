package com.karyo.reporting.api.v1.dto

/** One point in a daily series — `value` is a raw number for the sparkline polyline. */
data class RangePoint(val day: String, val value: Double)

/** A KPI tile. `value`/`delta` are preformatted display strings; `series` is raw for the sparkline. */
data class KpiTile(
    val key: String,        // "accuracy" | "throughput" | "cycleTime" | "utilization"
    val label: String,
    val value: String,      // e.g. "98.4%", "1,240/day", "6.2h", "72%"
    val delta: String?,     // e.g. "+0.6%", "-0.3h"; null when no prior/utilization
    val tone: String,       // "up" (good) | "warning" (down-bad)
    val series: List<RangePoint>,
)

/** Trend chart: outbound (picked) vs received daily units. */
data class KpiChart(val outbound: List<RangePoint>, val received: List<RangePoint>)

data class KpiDashboardResponse(
    val range: String,
    val rangeLabel: String,
    val tiles: List<KpiTile>,
    val chart: KpiChart,
)

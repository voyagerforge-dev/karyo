package com.karyo.reporting.service

import com.karyo.reporting.api.v1.dto.KpiChart
import com.karyo.reporting.api.v1.dto.KpiDashboardResponse
import com.karyo.reporting.api.v1.dto.KpiTile
import com.karyo.reporting.api.v1.dto.RangePoint
import com.karyo.reporting.repository.DailyCycleTime
import com.karyo.reporting.repository.DailyThroughput
import com.karyo.reporting.repository.KpiViewRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

@ApplicationScoped
class KpiDashboardService(private val repo: KpiViewRepository) {

    fun build(clientId: Long, range: KpiRange, now: Instant): KpiDashboardResponse {
        val (start, end) = range.window(now)
        val (pStart, pEnd) = range.priorWindow(now)

        // --- Accuracy (ratio) ---
        val acc = repo.accuracy(clientId, start, end)
        val accPrior = repo.accuracy(clientId, pStart, pEnd)
        val accRatio = ratio(acc.sumOf { it.accurateLines }, acc.sumOf { it.totalLines })
        val accPriorRatio = ratio(accPrior.sumOf { it.accurateLines }, accPrior.sumOf { it.totalLines })
        val accTile = KpiTile(
            key = "accuracy", label = "Inventory accuracy",
            value = KpiFormat.percent(accRatio),
            delta = if (accPrior.isEmpty()) null else KpiFormat.deltaPercent(accRatio, accPriorRatio),
            tone = KpiFormat.tone(true, accRatio, accPriorRatio),
            series = acc.map { RangePoint(it.day.toString(), ratio(it.accurateLines, it.totalLines) * 100) },
        )

        // --- Throughput (avg daily unitsPicked only — unitsShipped is a shipment count, not unit amount) ---
        val thr = repo.throughput(clientId, start, end)
        val thrPrior = repo.throughput(clientId, pStart, pEnd)
        fun avgPicked(rows: List<DailyThroughput>): Double =
            if (rows.isEmpty()) 0.0 else rows.sumOf { it.unitsPicked } / rows.size
        val thrCur = avgPicked(thr)
        val thrPriorVal = avgPicked(thrPrior)
        val thrTile = KpiTile(
            key = "throughput", label = "Throughput",
            value = KpiFormat.perDay(thrCur),
            delta = if (thrPrior.isEmpty()) null else KpiFormat.deltaUnits(thrCur, thrPriorVal),
            tone = KpiFormat.tone(true, thrCur, thrPriorVal),
            series = thr.map { RangePoint(it.day.toString(), it.unitsPicked) },
        )

        // --- Cycle time (avg hours = sum(total_hours)/sum(order_count)) ---
        val ct = repo.cycleTime(clientId, start, end)
        val ctPrior = repo.cycleTime(clientId, pStart, pEnd)
        fun avgHours(rows: List<DailyCycleTime>): Double {
            val n = rows.sumOf { it.orderCount }
            return if (n == 0L) 0.0 else rows.sumOf { it.totalHours } / n
        }
        val ctCur = avgHours(ct)
        val ctPriorVal = avgHours(ctPrior)
        val ctTile = KpiTile(
            key = "cycleTime", label = "Order cycle time",
            value = KpiFormat.hours(ctCur),
            delta = if (ctPrior.isEmpty()) null else KpiFormat.deltaHours(ctCur, ctPriorVal),
            tone = KpiFormat.tone(false, ctCur, ctPriorVal), // lower = good
            series = ct.map { RangePoint(it.day.toString(), if (it.orderCount == 0L) 0.0 else it.totalHours / it.orderCount) },
        )

        // --- Utilization (current snapshot, no trend) ---
        val (occ, usable) = repo.utilization(clientId)
        val util = if (usable == 0L) 0.0 else occ.toDouble() / usable
        val utilTile = KpiTile(
            key = "utilization", label = "Utilization",
            value = KpiFormat.percent(util), delta = null,
            tone = "up", series = emptyList(),
        )

        // Chart: outbound = unitsPicked (outbound-volume proxy); received = unitsReceived.
        // Both are true unit amounts — unitsShipped (shipment count) is intentionally excluded.
        val chart = KpiChart(
            outbound = thr.map { RangePoint(it.day.toString(), it.unitsPicked) },
            received = thr.map { RangePoint(it.day.toString(), it.unitsReceived) },
        )

        return KpiDashboardResponse(range.code, range.label, listOf(accTile, thrTile, ctTile, utilTile), chart)
    }

    private fun ratio(num: Long, den: Long): Double = if (den == 0L) 0.0 else num.toDouble() / den
}

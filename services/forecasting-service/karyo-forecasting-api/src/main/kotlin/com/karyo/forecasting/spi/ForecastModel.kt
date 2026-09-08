package com.karyo.forecasting.spi

import java.time.LocalDate

/**
 * Hybrid seam (mirrors v1.7a's Detector SPI): a deterministic EWMA model ships now;
 * statistical/ML models (Holt-Winters, Croston) implement the same interface later.
 */
interface ForecastModel {
    val key: String                                   // "ewma" for the built-in
    fun forecast(history: DemandHistory): DemandForecast
}

data class DailyDemand(val day: LocalDate, val quantity: Double)

data class DemandHistory(
    val sku: String,
    val daily: List<DailyDemand>,   // observed pick days; gaps are treated as zero-demand days
    val historyDays: Int,           // the window length requested
)

data class DemandForecast(
    val sku: String,
    val avgDailyDemand: Double,
    val demandStdDev: Double,       // sample std-dev of daily demand (0.0 when < 2 data points)
    val forecastNextNDays: Double,  // avgDailyDemand × horizonDays
    val dataPoints: Int,            // number of days with non-zero demand
    val confidence: String,         // "HIGH" | "MEDIUM" | "LOW"
)

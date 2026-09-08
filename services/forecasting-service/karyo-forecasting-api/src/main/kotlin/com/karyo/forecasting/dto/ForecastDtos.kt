package com.karyo.forecasting.dto

data class SkuForecastDto(
    val sku: String,
    val avgDailyDemand: Double,
    val forecastNextNDays: Double,
    val currentOnHand: Double,
    val suggestedReorderPoint: Int,
    val suggestedReorderQty: Int,
    val belowReorderPoint: Boolean,
    val confidence: String,
)

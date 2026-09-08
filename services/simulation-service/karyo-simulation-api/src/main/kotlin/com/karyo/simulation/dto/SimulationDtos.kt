package com.karyo.simulation.dto

/** Per-SKU backtest result surfaced by GET /api/v1/simulations/reorder. */
data class ReorderSimResultDto(
    val sku: String,
    val confidence: String,
    val baselineStockoutDays: Int,
    val suggestedStockoutDays: Int,
    val stockoutDaysAvoided: Int,
    val baselineFillRate: Double,
    val suggestedFillRate: Double,
    val fillRateDelta: Double,
    val baselineAvgOnHand: Double,
    val suggestedAvgOnHand: Double,
    val avgOnHandDelta: Double,
    val baselineReorderPoint: Int,
    val suggestedReorderPoint: Int,
    val orderQty: Int,
)

/** Aggregate headline across all simulated SKUs. */
data class ReorderSimSummaryDto(
    val skusSimulated: Int,
    val totalStockoutDaysAvoided: Int,
    val avgFillRateDelta: Double,
    val totalAvgOnHandDelta: Double,
)

data class ReorderSimResponseDto(
    val summary: ReorderSimSummaryDto,
    val rows: List<ReorderSimResultDto>,
)

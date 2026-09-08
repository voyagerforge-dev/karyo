package com.karyo.slotting.spi

/**
 * Hybrid seam (mirrors v1.7a Detector / v1.7b ForecastModel): a deterministic ABC-proximity
 * strategy ships now; affinity / ML / alternative-metric strategies implement the same interface
 * later with no change to the recommendation pipeline.
 */
interface SlottingStrategy {
    val key: String                                        // "abc-proximity" for the built-in
    fun recommend(input: SlottingInput): List<ReSlotSuggestion>
}

data class SkuVelocity(val sku: String, val pickFrequency: Int)
data class SkuSlot(val sku: String, val locationName: String, val orderIndex: Int, val zoneName: String?)

data class SlottingInput(
    val velocities: List<SkuVelocity>,
    val slots: List<SkuSlot>,
    val aThreshold: Double,
    val bThreshold: Double,
    val mismatchThreshold: Double,
)

enum class ReSlotDirection { PROMOTE, DEMOTE }

data class ReSlotSuggestion(
    val sku: String,
    val abcClass: String,          // "A" | "B" | "C"
    val velocityRank: Int,         // 1 = fastest
    val currentLocation: String,
    val currentOrderIndex: Int,
    val direction: ReSlotDirection,
    val reason: String,
    val severity: Double,          // abs(percentile divergence), 0..1
)

package com.karyo.replenishment.dto

import java.math.BigDecimal

data class ReplenishmentScanResult(
    val generated: List<GeneratedTask>,
    val shortfalls: List<ReplenishmentShortfall>,
)

/**
 * One minted REPLENISH transport order. [fixAssignmentId]/[itemDataAreaId] are mutually
 * exclusive: a Mode-1 (fix-face) row sets [fixAssignmentId] and leaves [itemDataAreaId] `null`
 * (the pre-R12b shape, unchanged); a Mode-2 (area-level, R12b/Task 6) row is the inverse.
 * [fixAssignmentId] widened to nullable (was non-null) to accommodate the area case — every
 * existing Mode-1 call site still passes a real id, so this is source-compatible.
 */
data class GeneratedTask(
    val taskId: Long,
    val orderNumber: String,
    val fixAssignmentId: Long?,
    val locationName: String,
    val itemDataNumber: String?,
    val unitLoadId: Long,
    val itemDataAreaId: Long? = null,
)

/**
 * A deficiency (fix-face or area) [reason] couldn't be resolved into a transport order this
 * pass. Same [fixAssignmentId]/[itemDataAreaId] mutual-exclusion shape as [GeneratedTask] — see
 * its KDoc. [reason] is `"NO_SOURCE"` (both modes) or `"NO_DESTINATION"` (area-only — R12b v1
 * has no location-search fallback the way Mode-1's destination, the fix face itself, never needs
 * one; see `ReplenishmentService.scanAreas`'s KDoc for the honest-gap rationale).
 */
data class ReplenishmentShortfall(
    val fixAssignmentId: Long?,
    val locationName: String,
    val itemDataNumber: String?,
    val currentAmount: BigDecimal,
    val minAmount: BigDecimal?,
    val reason: String,   // "NO_SOURCE" | "NO_DESTINATION"
    val itemDataAreaId: Long? = null,
)

data class ReplenishmentNeed(
    val fixAssignmentId: Long,
    val locationId: Long,
    val locationName: String,
    val itemDataId: Long,
    val itemDataNumber: String?,
    val currentAmount: BigDecimal,
    val minAmount: BigDecimal?,
    val desiredAmount: BigDecimal?,
    val belowMin: Boolean,
    val hasOpenTask: Boolean,
)

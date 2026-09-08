package com.karyo.monitors.spi

import com.karyo.monitors.vo.Severity

/**
 * Hybrid seam: deterministic detectors ship in v1.7a; statistical/ML detectors
 * implement the same interface later with no change to the alert pipeline.
 * Implementations are @ApplicationScoped CDI beans in monitors-core, indexed by monitorKey.
 */
interface Detector {
    /** Stable catalog key, e.g. "expiry-risk". Matches a MonitorDefinition + monitor_config row. */
    val monitorKey: String

    /** Returns one Finding per distinct problem scope; empty list == healthy. Must be read-only. */
    fun evaluate(ctx: DetectionContext): List<Finding>
}

data class DetectionContext(
    val clientId: Long,
    val config: MonitorRuntimeConfig,
)

data class MonitorRuntimeConfig(
    val monitorKey: String,
    val threshold: Double,
    val severity: Severity,
    val op: String,   // "<" or ">" — matches the mock's operator
)

data class Finding(
    val scope: String,          // "Zone B", "SKU WIDGET-1", "Order SO-2209"
    val reason: String,         // shown on the alert
    val suggestedFix: String,   // actionable next step
    val observedValue: Double,  // the metric value that tripped the threshold
)

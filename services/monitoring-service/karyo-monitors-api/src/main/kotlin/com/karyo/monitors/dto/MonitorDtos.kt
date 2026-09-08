package com.karyo.monitors.dto

import com.karyo.monitors.vo.AlertStatus
import com.karyo.monitors.vo.Severity
import java.time.Instant

data class MonitorChannels(val push: Boolean = false, val email: Boolean = false, val slack: Boolean = false)

/** Catalog entry + this tenant's config + live status, for the Monitors screen. */
data class MonitorDto(
    val key: String,
    val name: String,
    val metric: String,
    val op: String,
    val threshold: Double,
    val unit: String,
    val severity: Severity,
    val scope: String,
    val enabled: Boolean,
    val channels: MonitorChannels,
    val firing: Boolean,        // any open FIRING/ACK alert for this monitor
    val lastFired: Instant?,    // most recent alert first_fired_at, or null
)

/** PATCH body — all optional; only present fields are applied. */
data class MonitorConfigUpdate(
    val enabled: Boolean? = null,
    val threshold: Double? = null,
    val severity: Severity? = null,
    val op: String? = null,
    val channels: MonitorChannels? = null,
)

data class AlertDto(
    val id: Long,
    val monitorKey: String,
    val monitorName: String,
    val severity: Severity,
    val status: AlertStatus,
    val scope: String,
    val reason: String,
    val suggestedFix: String,
    val observedValue: Double,
    val firstFiredAt: Instant,
    val lastSeenAt: Instant,
    val resolvedAt: Instant?,
)

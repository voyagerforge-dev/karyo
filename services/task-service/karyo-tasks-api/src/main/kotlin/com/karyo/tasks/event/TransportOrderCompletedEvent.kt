package com.karyo.tasks.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Fired when a TransportOrder is completed (the unit load has physically moved). Carries
 * the resolved source and destination so downstream consumers (reporting, the copilot
 * feed) can reconstruct the move without a read-back. CDI + outbox.
 *
 * PT17: [itemDataId]/[amount]/[confirmedAmount]/[partial] are additive (all defaulted) —
 * outbox-compatible with rows written before this change. Mirror the order's own
 * denorm-at-creation ([amount]/[itemDataId]) and confirm-time ([confirmedAmount]) fields so a
 * consumer can read product + quantity off the event without a follow-up lookup. [partial] is
 * `true` only for a PT17 partial confirm; every other completion (whole-UL move, full
 * confirm-merge) reports `false`.
 */
data class TransportOrderCompletedEvent(
    val transportOrderId: Long,
    val orderNumber: String,
    val transportType: String,
    val unitLoadId: Long,
    val unitLoadLabel: String,
    val sourceLocationId: Long,
    val sourceLocationName: String,
    val destinationLocationId: Long,
    val destinationLocationName: String,
    val operatorId: String?,
    val clientId: Long,
    val occurredAt: Instant,
    val itemDataId: Long? = null,
    val amount: BigDecimal? = null,
    val confirmedAmount: BigDecimal? = null,
    val partial: Boolean = false,
)

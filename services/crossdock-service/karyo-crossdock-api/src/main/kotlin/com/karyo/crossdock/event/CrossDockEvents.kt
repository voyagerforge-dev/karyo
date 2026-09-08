package com.karyo.crossdock.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Cross-dock lifecycle events (Advanced Fulfillment pack). Every event is fired both as a
 * synchronous CDI event (in-process notification) AND written to the dormant outbox log
 * (eventType `cross-dock.matched` / `cross-dock.staged` / `cross-dock.expired` /
 * `cross-dock.completed` / `cross-dock.cancelled` respectively) -- the same dual-publish
 * convention every other module uses (see the Cross-Module Communication Map in AGENTS.md).
 */
data class CrossDockMatchFoundEvent(
    val crossDockOrderId: Long,
    val crossDockType: String,
    val goodsReceiptLineId: Long,
    val deliveryOrderLineId: Long,
    val itemDataId: Long,
    val amount: BigDecimal,
    val stagingLocationId: Long,
    val clientId: Long,
    val occurredAt: Instant,
)

data class CrossDockStagedEvent(
    val crossDockOrderId: Long,
    val clientId: Long,
    val occurredAt: Instant,
)

data class CrossDockExpiredEvent(
    val crossDockOrderId: Long,
    val action: String,
    val clientId: Long,
    val occurredAt: Instant,
)

data class CrossDockCompletedEvent(
    val crossDockOrderId: Long,
    val clientId: Long,
    val occurredAt: Instant,
)

/** Task 6: fired when `CrossDockLifecycleService.cancel` (crossdock-core; not linkable from
 *  this module) cancels a MATCHED/STAGED order (releases the slice, mints a fallback putaway
 *  when a unit load exists). */
data class CrossDockCancelledEvent(
    val crossDockOrderId: Long,
    val clientId: Long,
    val occurredAt: Instant,
)

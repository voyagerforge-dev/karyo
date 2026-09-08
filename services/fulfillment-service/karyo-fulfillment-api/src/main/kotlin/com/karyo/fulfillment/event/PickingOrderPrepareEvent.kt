package com.karyo.fulfillment.event

import java.math.BigDecimal

/**
 * Pure extension hook (row 16, myWMS-faithful — myWMS itself ships no observer for this event
 * either). Fired SYNCHRONOUSLY by `PickOrderService.releaseToPicking`, once per grouping batch,
 * AFTER the order's reservations have been flattened + grouped but BEFORE any persistence (no
 * pick container, PickOrder, or Pick row exists yet for this release). Mirrors the
 * `ItemDataStateChangedEvent` / `Event<T>.fire()` idiom (see `ProductService`).
 *
 * An observer claims a candidate pick by adding its list index (into [plannedPicks]) to
 * [consumedPickIndexes] — typically because it is materializing that reservation slice into an
 * order of its OWN (e.g. a future cross-docking or wave-planning extension). The generator persists
 * a PickOrder + Picks only for the UNCONSUMED remainder; if every index is consumed, no PickOrder
 * is created at all and `releaseToPicking` throws
 * [com.karyo.fulfillment.exception.FulfillmentException.AllPicksConsumedByExtension] (mapped to
 * 409) rather than fabricating an empty order — the REST caller learns an extension took the work.
 *
 * v1.3 ships NO built-in observer: this is a pure seam, proven only by test-only observers in
 * `PickPrepareEventTest`.
 */
data class PickingOrderPrepareEvent(
    val externalNumber: String,
    val deliveryOrderId: Long,
    val plannedPicks: List<PlannedPickRef>,
    val consumedPickIndexes: MutableSet<Int> = mutableSetOf(),
)

/**
 * Read-only reference to one candidate pick (one reservation slice) offered to observers of
 * [PickingOrderPrepareEvent]. Deliberately a separate, narrower type from
 * `com.karyo.fulfillment.spi.PlannedPick` (the internal grouping-strategy DTO) so this
 * cross-module-facing event payload can evolve independently of the SPI's internal shape.
 */
data class PlannedPickRef(
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val sourceStockUnitId: Long,
    val deliveryOrderLineId: Long,
    val lotNumber: String?,
)

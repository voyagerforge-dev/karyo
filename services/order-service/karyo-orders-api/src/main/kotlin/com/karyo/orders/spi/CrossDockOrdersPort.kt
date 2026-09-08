package com.karyo.orders.spi

import java.math.BigDecimal

/**
 * Cross-module read/write seam for the cross-docking engine (Advanced Fulfillment pack).
 * Declared Apache-side; the paid module consumes it. All methods tenant-scoped by clientId.
 */
interface CrossDockOrdersPort {
    /** The ASN line's cross-dock target order id for this goods-receipt line, or null. */
    fun crossDockTargetFor(goodsReceiptLineId: Long, clientId: Long): Long?

    /** Open, unfulfilled delivery-order lines for the item, earliest ship-by first. */
    fun openLineCandidates(itemDataId: Long, clientId: Long): List<CrossDockCandidateLine>

    /** Open, unfulfilled lines of the given delivery order that match the item, or empty. */
    fun openLinesOf(deliveryOrderId: Long, itemDataId: Long, clientId: Long): List<CrossDockCandidateLine>

    /** Reserve a concrete stock-unit slice against the line. False if the line no longer needs it. */
    fun reserveSlice(deliveryOrderLineId: Long, stockUnitId: Long, amount: BigDecimal, clientId: Long): Boolean

    /** Release a previously reserved slice (cancel/expiry path). Idempotent. */
    fun releaseSlice(deliveryOrderLineId: Long, stockUnitId: Long, clientId: Long)

    /** True while the slice reservation still exists (used to detect COMPLETED vs stuck). */
    fun sliceExists(deliveryOrderLineId: Long, stockUnitId: Long, clientId: Long): Boolean
}

data class CrossDockCandidateLine(
    val deliveryOrderLineId: Long,
    val deliveryOrderId: Long,
    val openAmount: BigDecimal,
    val shipBy: java.time.Instant?,
)

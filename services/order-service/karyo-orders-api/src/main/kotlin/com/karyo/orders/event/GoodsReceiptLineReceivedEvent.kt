package com.karyo.orders.event

import java.math.BigDecimal
import java.time.Instant

/**
 * Fired for every received goods-receipt line (CDI + outbox).
 *
 * This event is the putaway trigger for sub-phase 2.3: the tasks module observes it
 * and auto-creates a PUTAWAY TransportOrder. The payload therefore carries
 * everything that consumer needs without a read-back — the unit load to move
 * ([unitLoadId]/[unitLoadLabel]), where it currently sits ([locationId]/
 * [locationName] — the receiving dock), and [qaHold] so held stock can be
 * excluded from automatic putaway.
 *
 * [qaHold] means "ANY receive-time lock is set" (since B5 it is derived from the
 * line's lockType, not only a QUALITY_FAULT) — i.e. "this stock is not ready,
 * skip putaway". Name and meaning are a stable contract; do not rename.
 *
 * V424: [asnId] is the ASN the LINE counted against (`asnLine.asn.id`), not a
 * receipt-level scalar — a receipt may now span several ASNs. Still null for a
 * blind line. Field name/meaning stay a stable contract for webhook consumers.
 *
 * V425 (inbound-completion row 7 residual): [storageStrategyId] carries the line's
 * validated putaway strategy override, if any, so the tasks module observer can persist
 * it onto the auto-created `TransportOrder` and thread it into the location finder.
 */
data class GoodsReceiptLineReceivedEvent(
    val goodsReceiptId: Long,
    val goodsReceiptLineId: Long,
    val asnId: Long?,
    val itemDataId: Long,
    val amount: BigDecimal,
    val stockUnitId: Long,
    val unitLoadId: Long,
    val unitLoadLabel: String,
    val locationId: Long,
    val locationName: String,
    val qaHold: Boolean,
    val clientId: Long,
    val occurredAt: Instant,
    val storageStrategyId: Long? = null,
)

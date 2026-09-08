package com.karyo.orders.event

import java.math.BigDecimal

/**
 * B3 (six-hard-items §3, option B3-2): fired when a received goods-receipt line is
 * totally reversed (CDI + outbox) — [GoodsReceiptService.reverseLine]'s counterpart
 * to [GoodsReceiptLineReceivedEvent].
 *
 * This is the putaway UNDO trigger: the tasks module observes it and cancels the
 * PUTAWAY [com.karyo.tasks.domain.model.TransportOrder] created for this line in
 * the SAME transaction (synchronous, default phase — unlike the received event's
 * AFTER_SUCCESS/REQUIRES_NEW observer). If the task is already STARTED the observer
 * throws and the whole reversal rolls back, so an operator mid-putaway never has
 * the work vanish underneath them.
 */
data class GoodsReceiptLineReversedEvent(
    val goodsReceiptLineId: Long,
    val goodsReceiptId: Long,
    val stockUnitId: Long,
    val unitLoadId: Long,
    val itemDataId: Long,
    val amount: BigDecimal,
    val clientId: Long,
)

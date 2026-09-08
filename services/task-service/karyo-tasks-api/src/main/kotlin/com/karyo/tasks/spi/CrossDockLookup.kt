package com.karyo.tasks.spi

/**
 * Inbound seam: lets the (paid) cross-docking engine answer whether it already intercepted a
 * received goods-receipt line, without tasks depending on the crossdock module at all.
 *
 * Answered by the cross-docking engine (paid module, `karyo-crossdock-core`). The auto-putaway
 * observer ([com.karyo.tasks.service.TaskService.onGoodsReceiptLineReceived]) skips lines the
 * engine intercepted. The interceptor runs synchronously inside the receiving transaction,
 * before this observer's `AFTER_SUCCESS` phase, so by the time the observer runs, an existing
 * cross-dock order for the line is authoritative. When the paid module is absent or unlicensed,
 * no bean implementing this interface exists at all, so the consumer injects
 * `jakarta.enterprise.inject.Instance<CrossDockLookup>` and treats an empty (unresolvable)
 * Instance as "never intercepted": the WMS degrades to its ordinary always-putaway behavior.
 */
interface CrossDockLookup {
    fun existsForReceiptLine(goodsReceiptLineId: Long): Boolean
}

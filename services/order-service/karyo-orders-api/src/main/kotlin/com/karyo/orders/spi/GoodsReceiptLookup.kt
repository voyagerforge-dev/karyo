package com.karyo.orders.spi

import com.karyo.orders.vo.ReceiptSummary

/**
 * In-process goods-receipt lookup contract. Implemented by orders-core and consumed by
 * other modules (e.g. inventory, for the supplier/ASN/received-at read on a stock unit)
 * instead of a cross-service REST client.
 */
interface GoodsReceiptLookup {
    /** Batch: stock-unit id -> its goods-receipt summary (units not received via a GR are absent). */
    fun findByStockUnitIds(ids: Set<Long>): Map<Long, ReceiptSummary>
}

package com.karyo.orders.vo

import java.time.Instant

/**
 * Cross-module read summary of the [com.karyo.orders.domain.model.GoodsReceipt] /
 * [com.karyo.orders.domain.model.Asn] that produced a given stock unit -- the minimal shape
 * other modules (inventory) need to show supplier/ASN/received-at on a stock unit without
 * depending on the orders-core entities. Consumed through the
 * [com.karyo.orders.spi.GoodsReceiptLookup] SPI.
 */
data class ReceiptSummary(
    val receiptNumber: String?,
    val asnNumber: String?,
    val supplierName: String?,
    val receivedAt: Instant?,
)

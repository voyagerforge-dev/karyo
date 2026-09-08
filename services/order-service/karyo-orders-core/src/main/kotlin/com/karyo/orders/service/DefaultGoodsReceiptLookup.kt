package com.karyo.orders.service

import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.GoodsReceiptLineRepository
import com.karyo.orders.repository.GoodsReceiptRepository
import com.karyo.orders.spi.GoodsReceiptLookup
import com.karyo.orders.vo.ReceiptSummary
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import java.time.ZoneOffset

/**
 * Default in-process implementation of [GoodsReceiptLookup], scoped to the current tenant like
 * [com.karyo.fulfillment.service.DefaultShipmentLookup]. A stock unit that was never received
 * through a [GoodsReceipt] (e.g. a manually-created adjustment) is simply absent from the
 * result map -- callers (e.g. inventory) treat absence as an honest gap (keep the `--`
 * placeholder), never fabricate supplier/ASN/received data.
 */
@ApplicationScoped
class DefaultGoodsReceiptLookup(
    private val goodsReceiptLineRepository: GoodsReceiptLineRepository,
    private val goodsReceiptRepository: GoodsReceiptRepository,
    private val asnRepository: AsnRepository,
    private val tenantContext: TenantContext,
) : GoodsReceiptLookup {

    override fun findByStockUnitIds(ids: Set<Long>): Map<Long, ReceiptSummary> {
        if (ids.isEmpty()) return emptyMap()
        val clientId = tenantContext.clientId

        val lines = goodsReceiptLineRepository.findByStockUnitIds(ids, clientId)
        if (lines.isEmpty()) return emptyMap()

        // Batch-load the distinct receipts once -- never one query per line.
        val receiptIds = lines.map { it.goodsReceipt.id!! }.toSet()
        val receiptsById: Map<Long, GoodsReceipt> =
            goodsReceiptRepository.findByIds(receiptIds, clientId).associateBy { it.id!! }

        // V424: the ASN is per-LINE (asnLineId), not per-receipt -- a receipt may span
        // several ASNs. Batch: distinct asnLine ids -> owning Asn, one query.
        val asnLineIds = lines.mapNotNull { it.asnLineId }.toSet()
        val asnByLineId: Map<Long, Asn> = asnRepository.findAsnsForLines(asnLineIds, clientId)

        return lines.mapNotNull { line ->
            val receipt = receiptsById[line.goodsReceipt.id] ?: return@mapNotNull null
            val asn = line.asnLineId?.let { asnByLineId[it] }
            line.stockUnitId to ReceiptSummary(
                receiptNumber = receipt.receiptNumber,
                asnNumber = asn?.asnNumber,
                supplierName = asn?.supplierName,
                // B7: the operator-entered (backdatable) physical-arrival date wins over
                // the record-creation timestamp; a LocalDate is widened to the summary's
                // Instant as start-of-day UTC. Falls back to `created` when never entered.
                receivedAt = receipt.receiptDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
                    ?: receipt.created,
            )
        }.toMap()
    }
}

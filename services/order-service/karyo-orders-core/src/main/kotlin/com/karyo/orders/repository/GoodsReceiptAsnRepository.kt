package com.karyo.orders.repository

import com.karyo.orders.domain.model.GoodsReceiptAsn
import com.karyo.orders.domain.model.GoodsReceiptAsnId
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped

/**
 * Join-row repository for the GoodsReceipt<->Asn many-to-many (V424). Plain ID-pair
 * CRUD over [GoodsReceiptAsn] -- see its KDoc for why this is not a
 * [jakarta.persistence.ManyToMany] mapping.
 */
@ApplicationScoped
class GoodsReceiptAsnRepository : PanacheRepositoryBase<GoodsReceiptAsn, GoodsReceiptAsnId> {

    fun exists(receiptId: Long, asnId: Long): Boolean =
        count("id.goodsReceiptId = ?1 and id.asnId = ?2", receiptId, asnId) > 0

    /** Idempotent: a no-op when the pair is already attached (auto-attach dedupe on a repeat receive). */
    fun attach(receiptId: Long, asnId: Long) {
        if (!exists(receiptId, asnId)) {
            persist(GoodsReceiptAsn(receiptId, asnId))
        }
    }

    /** @return true if a row was removed; false means the pair was never attached. */
    fun detach(receiptId: Long, asnId: Long): Boolean =
        delete("id.goodsReceiptId = ?1 and id.asnId = ?2", receiptId, asnId) > 0

    fun findAsnIdsFor(receiptId: Long): List<Long> =
        list("id.goodsReceiptId = ?1", receiptId).map { it.id.asnId }

    /** Batch variant for a page of receipts -- the [com.karyo.orders.service.GoodsReceiptService] toResponse N+1 fix. */
    fun findAsnIdsFor(receiptIds: Set<Long>): Map<Long, List<Long>> {
        if (receiptIds.isEmpty()) return emptyMap()
        return list("id.goodsReceiptId in ?1", receiptIds)
            .groupBy({ it.id.goodsReceiptId }, { it.id.asnId })
    }
}

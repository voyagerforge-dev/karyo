package com.karyo.orders.repository

import com.karyo.orders.domain.model.GoodsReceiptLine
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class GoodsReceiptLineRepository : PanacheRepository<GoodsReceiptLine> {

    fun findByGoodsReceiptId(goodsReceiptId: Long): List<GoodsReceiptLine> =
        list("goodsReceipt.id", goodsReceiptId)

    /** Tenant-scoped single lookup (join through the owning [GoodsReceiptLine.goodsReceipt]). */
    fun findByIdAndClient(id: Long, clientId: Long): GoodsReceiptLine? =
        find("id = ?1 and goodsReceipt.clientId = ?2", id, clientId).firstResult()

    /** Tenant-scoped batch lookup by stock-unit id (join through the owning [GoodsReceiptLine.goodsReceipt]). */
    fun findByStockUnitIds(ids: Set<Long>, clientId: Long): List<GoodsReceiptLine> {
        if (ids.isEmpty()) return emptyList()
        return list("stockUnitId in ?1 and goodsReceipt.clientId = ?2", ids, clientId)
    }
}

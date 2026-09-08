package com.karyo.orders.repository

import com.karyo.orders.domain.model.GoodsReceipt
import com.karyo.orders.vo.OrderState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class GoodsReceiptRepository : PanacheRepository<GoodsReceipt> {

    fun findByIdAndClient(id: Long, clientId: Long): GoodsReceipt? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /**
     * Floor-work-inbox pool (Task 6, `ReceivingWorkProvider`): CREATED or STARTED, unclaimed,
     * and NOT paused. A paused receipt (see [com.karyo.orders.service.GoodsReceiptService.pause])
     * is deliberately excluded — it is parked (an operator opted out mid-session), not floor
     * work, and only resurfaces once [com.karyo.orders.service.GoodsReceiptService.resume]
     * clears [GoodsReceipt.pausedAt].
     */
    fun findClaimable(clientId: Long): List<GoodsReceipt> = list(
        "clientId = ?1 and state in (?2, ?3) and operatorId is null and pausedAt is null",
        clientId, OrderState.CREATED.code, OrderState.STARTED.code,
    )

    /**
     * Receipts currently claimed by [operatorId] — same open + not-paused gate as
     * [findClaimable], so a receipt that gets paused after being claimed also drops off the
     * "mine" list (same "parked, not floor work" rationale, even though pause leaves
     * [GoodsReceipt.operatorId] set).
     */
    fun findClaimedBy(clientId: Long, operatorId: String): List<GoodsReceipt> = list(
        "clientId = ?1 and state in (?2, ?3) and operatorId = ?4 and pausedAt is null",
        clientId, OrderState.CREATED.code, OrderState.STARTED.code, operatorId,
    )

    /** Tenant-scoped batch lookup. */
    fun findByIds(ids: Set<Long>, clientId: Long): List<GoodsReceipt> {
        if (ids.isEmpty()) return emptyList()
        return list("id in ?1 and clientId = ?2", ids, clientId)
    }

    fun findByReceiptNumber(receiptNumber: String, clientId: Long): GoodsReceipt? =
        find("receiptNumber = ?1 and clientId = ?2", receiptNumber, clientId).firstResult()

    /**
     * Tenant-scoped search with optional state and ASN filters. [asnId] now filters
     * through the V424 join table (a receipt may be bound to several ASNs) --
     * the query-param name stays `asnId` for API compatibility.
     */
    fun search(clientId: Long, state: Int?, asnId: Long?, sort: Sort): PanacheQuery<GoodsReceipt> {
        val query = StringBuilder("clientId = :clientId")
        val params = Parameters.with("clientId", clientId)

        if (state != null) {
            query.append(" and state = :state")
            params.and("state", state)
        }
        if (asnId != null) {
            query.append(" and id in (select ga.id.goodsReceiptId from GoodsReceiptAsn ga where ga.id.asnId = :asnId)")
            params.and("asnId", asnId)
        }
        return find(query.toString(), sort, params)
    }
}

package com.karyo.orders.repository

import com.karyo.orders.domain.model.AsnUlAdvice
import com.karyo.orders.vo.OrderState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * [AsnUlAdvice] CRUD + the cross-ASN receive-time match query. Advices are managed
 * independently of [com.karyo.orders.domain.model.Asn]'s lifecycle (created/deleted
 * one at a time, matched across a whole receipt's ASN set) — a dedicated repository,
 * mirroring [AsnRepository]/[GoodsReceiptLineRepository], rather than routing through
 * the parent's `ulAdvices` collection.
 */
@ApplicationScoped
class AsnUlAdviceRepository : PanacheRepository<AsnUlAdvice> {

    /** Tenant-scoped via the parent ASN's clientId (advices carry no clientId of their own). */
    fun findByIdAndAsn(adviceId: Long, asnId: Long, clientId: Long): AsnUlAdvice? =
        find("id = ?1 and asn.id = ?2 and asn.clientId = ?3", adviceId, asnId, clientId).firstResult()

    fun findByAsnId(asnId: Long): List<AsnUlAdvice> =
        list("asn.id = ?1 order by id asc", asnId)

    fun existsByAsnAndLabel(asnId: Long, labelId: String): Boolean =
        count("asn.id = ?1 and labelId = ?2", asnId, labelId) > 0

    /**
     * The advice (if any) [AsnUlAdviceService.match] previously stamped FINISHED against
     * [receiptLineId] — [GoodsReceiptService.reverseLine]'s reopen hook. At most one row
     * can match: [AsnUlAdviceService.match] only ever sets this column once, on the OPEN
     * advice it found, and a receipt line id is never reused across receives.
     */
    fun findByMatchedReceiptLineId(receiptLineId: Long): AsnUlAdvice? =
        find("matchedReceiptLineId = ?1", receiptLineId).firstResult()

    /**
     * Cross-ASN receive-time match ([com.karyo.orders.service.AsnUlAdviceService.match]):
     * the OPEN (CREATED) advice carrying [labelId] on any of [asnIds], if any. [asnIds]
     * is expected to already be tenant-scoped (the receipt's attached-ASN set, itself
     * only ever tenant-matched ASNs) — no extra clientId filter needed here.
     */
    fun findOpenByLabel(asnIds: Set<Long>, labelId: String): AsnUlAdvice? {
        if (asnIds.isEmpty()) return null
        return find("asn.id in ?1 and labelId = ?2 and state = ?3", asnIds, labelId, OrderState.CREATED.code)
            .firstResult()
    }
}

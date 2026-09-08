package com.karyo.orders.repository

import com.karyo.orders.domain.model.Asn
import com.karyo.orders.domain.model.AsnLine
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AsnRepository : PanacheRepository<Asn> {

    fun findByIdAndClient(id: Long, clientId: Long): Asn? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /** Tenant-scoped batch lookup. */
    fun findByIds(ids: Set<Long>, clientId: Long): List<Asn> {
        if (ids.isEmpty()) return emptyList()
        return list("id in ?1 and clientId = ?2", ids, clientId)
    }

    fun findByAsnNumber(asnNumber: String, clientId: Long): Asn? =
        find("asnNumber = ?1 and clientId = ?2", asnNumber, clientId).firstResult()

    /**
     * Tenant-scoped: the ASN owning ASN-line [asnLineId], or null when the line
     * doesn't exist or belongs to a different tenant. The GR receive-time auto-attach
     * seam resolves the ASN this way -- it only has a line id, not an ASN id, until
     * this lookup runs.
     */
    fun findByLineId(asnLineId: Long, clientId: Long): Asn? =
        find("select a from Asn a join a.lines l where l.id = ?1 and a.clientId = ?2", asnLineId, clientId)
            .firstResult()

    /**
     * Tenant-scoped: the [AsnLine] itself (not the owning [Asn]) -- used by
     * [com.karyo.orders.service.DefaultCrossDockOrdersPort.crossDockTargetFor] to read
     * `crossDockDeliveryOrderId` without loading the whole ASN aggregate.
     */
    fun findLineById(asnLineId: Long, clientId: Long): AsnLine? =
        getEntityManager()
            .createQuery(
                "select l from Asn a join a.lines l where l.id = :lineId and a.clientId = :clientId",
                AsnLine::class.java,
            )
            .setParameter("lineId", asnLineId)
            .setParameter("clientId", clientId)
            .resultList
            .firstOrNull()

    /**
     * Tenant-scoped batch: [asnLineId] -> owning [Asn], one query (join fetch), for
     * every id in [asnLineIds] -- the [com.karyo.orders.service.DefaultGoodsReceiptLookup]
     * per-line ASN-number/supplier derivation (never one query per line).
     */
    fun findAsnsForLines(asnLineIds: Set<Long>, clientId: Long): Map<Long, Asn> {
        if (asnLineIds.isEmpty()) return emptyMap()
        val asns = list(
            "select distinct a from Asn a join fetch a.lines l where l.id in ?1 and a.clientId = ?2",
            asnLineIds,
            clientId,
        )
        return asns.flatMap { asn -> asn.lines.filter { it.id in asnLineIds }.map { it.id!! to asn } }.toMap()
    }

    /** Tenant-scoped: true if ASN [asnId] owns any line in [asnLineIds] -- the GR-detach 409 guard. */
    fun anyLineBelongsTo(asnLineIds: Set<Long>, asnId: Long, clientId: Long): Boolean {
        if (asnLineIds.isEmpty()) return false
        return count(
            "id = ?1 and clientId = ?2 and id in (select l.asn.id from AsnLine l where l.id in ?3)",
            asnId,
            clientId,
            asnLineIds,
        ) > 0
    }

    /**
     * Tenant-scoped search with optional state filter and free-text query over
     * asnNumber/externalNumber (case-insensitive contains).
     */
    fun search(clientId: Long, state: Int?, q: String?, sort: Sort): PanacheQuery<Asn> {
        val query = StringBuilder("clientId = :clientId")
        val params = Parameters.with("clientId", clientId)

        if (state != null) {
            query.append(" and state = :state")
            params.and("state", state)
        }
        if (!q.isNullOrBlank()) {
            query.append(" and (lower(asnNumber) like :q or lower(externalNumber) like :q)")
            params.and("q", "%${q.lowercase()}%")
        }
        return find(query.toString(), sort, params)
    }
}

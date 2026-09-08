package com.karyo.stocktaking.repository

import com.karyo.stocktaking.domain.model.CountOrder
import com.karyo.stocktaking.vo.CountOrderState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CountOrderRepository : PanacheRepository<CountOrder> {

    fun findBySession(sessionId: Long): List<CountOrder> = list("sessionId", sessionId)

    fun findByClientId(clientId: Long): List<CountOrder> = list("clientId", clientId)

    fun findByIdAndClient(id: Long, clientId: Long): CountOrder? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /** OPEN count work: GENERATED, unclaimed. */
    fun findClaimable(clientId: Long): List<CountOrder> =
        list("clientId = ?1 and state = ?2 and operatorId is null", clientId, CountOrderState.GENERATED.code)

    /** Count work claimed by an operator: GENERATED with operator set. */
    fun findClaimedBy(clientId: Long, operatorId: String): List<CountOrder> =
        list("clientId = ?1 and state = ?2 and operatorId = ?3", clientId, CountOrderState.GENERATED.code, operatorId)

    /**
     * Campaign rollup: order counts grouped by [CountOrderState] code, across every session
     * under [campaignId] -- the one grouped query behind `ordersByState` on
     * [com.karyo.stocktaking.dto.CountCampaignRollupView]. States with zero orders are simply
     * absent from the map; the caller defaults them to 0.
     */
    @Suppress("UNCHECKED_CAST")
    fun rollupStateCounts(campaignId: Long): Map<Int, Long> {
        val rows = getEntityManager()
            .createQuery(
                "select o.state, count(o) from CountOrder o " +
                    "where o.sessionId in (select s.id from CountSession s where s.campaignId = :campaignId) " +
                    "group by o.state"
            )
            .setParameter("campaignId", campaignId)
            .resultList as List<Array<Any>>
        return rows.associate { (it[0] as Int) to (it[1] as Long) }
    }

    /**
     * Per-session order-state rollup for a whole page of sessions -- ONE grouped query for
     * every session in [sessionIds] (never one query per session), mirroring [rollupStateCounts]
     * but grouped by session too. The GET /count-sessions summary projection's `orderCount`/
     * `countedCount`/`finishedCount` fields are derived from this map (defect-burndown row 8).
     *
     * [sessionIds] empty short-circuits to `emptyMap()` -- a JPQL `in ()` is invalid SQL, not an
     * empty-result query.
     */
    @Suppress("UNCHECKED_CAST")
    fun rollupStateCountsBySessions(sessionIds: List<Long>): Map<Long, Map<Int, Long>> {
        if (sessionIds.isEmpty()) return emptyMap()
        val rows = getEntityManager()
            .createQuery(
                "select o.sessionId, o.state, count(o) from CountOrder o " +
                    "where o.sessionId in :sessionIds group by o.sessionId, o.state"
            )
            .setParameter("sessionIds", sessionIds)
            .resultList as List<Array<Any>>
        return rows.groupBy({ it[0] as Long }, { (it[1] as Int) to (it[2] as Long) })
            .mapValues { it.value.toMap() }
    }
}

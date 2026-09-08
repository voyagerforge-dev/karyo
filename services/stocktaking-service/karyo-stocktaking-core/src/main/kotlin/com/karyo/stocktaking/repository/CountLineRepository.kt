package com.karyo.stocktaking.repository

import com.karyo.stocktaking.domain.model.CountLine
import com.karyo.stocktaking.vo.CountLineState
import com.karyo.stocktaking.vo.CountSessionState
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CountLineRepository : PanacheRepository<CountLine> {

    fun findByOrder(orderId: Long): List<CountLine> = list("countOrderId", orderId)

    fun findByClientId(clientId: Long): List<CountLine> = list("clientId", clientId)

    /**
     * Campaign rollup: count of discrepancy lines (counted amount recorded and different from
     * planned, on a line in [CountLineState.COUNTED] or [CountLineState.FINISHED]) across every
     * session under [campaignId] -- the second (and last) grouped/aggregate query behind
     * [com.karyo.stocktaking.dto.CountCampaignRollupView.discrepancyLines].
     *
     * Deliberately an explicit `IN (COUNTED, FINISHED)` rather than `state >= COUNTED`: CANCELLED
     * (800) is numerically above COUNTED (500) but is NOT a live discrepancy.
     * [com.karyo.stocktaking.service.StocktakingService.recount] cancels the discrepant order's
     * lines without clearing `countedAmount` (the mismatched value is kept for audit), so a
     * `>=` filter would count a superseded, cancelled discrepancy forever even after a clean
     * recount closes the story out.
     */
    fun countDiscrepancies(campaignId: Long): Long =
        getEntityManager()
            .createQuery(
                "select count(l) from CountLine l where l.countOrderId in (" +
                    "  select o.id from CountOrder o where o.sessionId in (" +
                    "    select s.id from CountSession s where s.campaignId = :campaignId" +
                    "  )" +
                    ") and l.countedAmount is not null and l.countedAmount <> l.plannedAmount " +
                    "and l.state in (:countedState, :finishedState)",
                Long::class.java,
            )
            .setParameter("campaignId", campaignId)
            .setParameter("countedState", CountLineState.COUNTED.code)
            .setParameter("finishedState", CountLineState.FINISHED.code)
            .singleResult

    /**
     * Row 18, stocktaking half of [com.karyo.inventory.api.spi.PurgeBlockerLookup.blockedStockUnitIds]:
     * distinct [CountLine.stockUnitId] among [stockUnitIds] on a line whose count order sits
     * under an OPEN [com.karyo.stocktaking.domain.model.CountSession] for [clientId]. A closed
     * session's lines are historical (see [com.karyo.inventory.api.spi.PurgeBlockerLookup]'s
     * KDoc), so they are deliberately excluded. Batched over the whole candidate set. Callers
     * must not pass an empty collection.
     */
    fun findOpenSessionStockUnitIds(stockUnitIds: Collection<Long>, clientId: Long): Set<Long> =
        openSessionQuery("l.stockUnitId", stockUnitIds, clientId)

    /** Unit-load half of [findOpenSessionStockUnitIds] -- same OPEN-session join, keyed by
     *  [CountLine.unitLoadId] (nullable) instead. */
    fun findOpenSessionUnitLoadIds(unitLoadIds: Collection<Long>, clientId: Long): Set<Long> =
        openSessionQuery("l.unitLoadId", unitLoadIds, clientId)

    private fun openSessionQuery(selectField: String, candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        getEntityManager()
            .createQuery(
                """
                select distinct $selectField from CountLine l
                where $selectField in :ids and l.clientId = :clientId
                  and l.countOrderId in (
                      select o.id from CountOrder o where o.sessionId in (
                          select s.id from CountSession s where s.clientId = :clientId and s.state = :open
                      )
                  )
                """.trimIndent(),
                Long::class.java,
            )
            .setParameter("ids", candidateIds)
            .setParameter("clientId", clientId)
            .setParameter("open", CountSessionState.OPEN.code)
            .resultList
            .toSet()
}

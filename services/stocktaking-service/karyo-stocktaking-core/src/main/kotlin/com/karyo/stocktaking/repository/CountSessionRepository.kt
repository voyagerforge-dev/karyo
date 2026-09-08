package com.karyo.stocktaking.repository

import com.karyo.stocktaking.domain.model.CountSession
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CountSessionRepository : PanacheRepository<CountSession> {

    fun findByClientId(clientId: Long): List<CountSession> = list("clientId", clientId)

    fun findByCampaign(campaignId: Long): List<CountSession> = list("campaignId", campaignId)

    /** Rollup count -- how many sessions belong to [campaignId] (any state). */
    fun countByCampaign(campaignId: Long): Long = count("campaignId", campaignId)

    /**
     * Paginated, `created DESC` page of [clientId]'s sessions -- the query behind the
     * GET /count-sessions summary projection (defect-burndown row 8: the previous unpaginated
     * nested-graph GET was a warehouse-scale OOM risk). [size] is the caller's job to clamp
     * (see [com.karyo.stocktaking.service.StocktakingService.listSessions]'s `MAX_PAGE_SIZE`
     * clamp, mirroring `DocumentStoreService.list`'s local clamp).
     */
    fun findByClientIdPaginated(clientId: Long, page: Int, size: Int): PanacheQuery<CountSession> =
        find("clientId", Sort.descending("created"), clientId).page(Page.of(page, size))
}

package com.karyo.stocktaking.service

import com.karyo.sequence.SequenceNumberService
import com.karyo.stocktaking.domain.model.CountCampaign
import com.karyo.stocktaking.dto.CountCampaignRollupView
import com.karyo.stocktaking.dto.CountCampaignView
import com.karyo.stocktaking.dto.CreateCampaignRequest
import com.karyo.stocktaking.dto.OrdersByStateView
import com.karyo.stocktaking.exception.StocktakingException
import com.karyo.stocktaking.repository.CountCampaignRepository
import com.karyo.stocktaking.repository.CountLineRepository
import com.karyo.stocktaking.repository.CountOrderRepository
import com.karyo.stocktaking.repository.CountSessionRepository
import com.karyo.stocktaking.vo.CountCampaignState
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.stocktaking.vo.CountType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * CRUD + close + rollup for [CountCampaign] (St1) -- a real lifecycle above
 * [com.karyo.stocktaking.domain.model.CountSession], split out from [StocktakingService] so
 * that class stays under detekt's `TooManyFunctions` ceiling. [StocktakingService] retains its
 * own campaign check (`validateCampaignForStart`) since that one is intrinsic to `startCount`,
 * not campaign CRUD.
 */
@ApplicationScoped
class CountCampaignService(
    private val campaignRepository: CountCampaignRepository,
    private val sessionRepository: CountSessionRepository,
    private val orderRepository: CountOrderRepository,
    private val countLineRepository: CountLineRepository,
    private val sequenceNumberService: SequenceNumberService,
) {

    /** Creates a new [CountCampaign] in state OPEN. [CreateCampaignRequest.type] defaults to
     *  CYCLE; any value must be a legal [CountType] name or [StocktakingException.InvalidState] (409). */
    @Transactional
    fun createCampaign(request: CreateCampaignRequest, clientId: Long): CountCampaignView {
        val type = validateCampaignType(request.type)
        val campaign = CountCampaign().apply {
            this.clientId = clientId
            campaignNumber = sequenceNumberService.next("count.campaignNumber", "CC-$clientId", clientId, MAX_NUMBER_LENGTH) { candidate ->
                campaignRepository.find("campaignNumber = ?1 and clientId = ?2", candidate, clientId).firstResult() == null
            }
            name = request.name
            this.type = type.name
            state = CountCampaignState.OPEN.code
            started = Instant.now()
        }
        campaignRepository.persist(campaign)
        return toCampaignView(campaign)
    }

    /** All campaigns for [clientId] (plain views, no rollup -- the list screen). */
    fun listCampaigns(clientId: Long): List<CountCampaignView> =
        campaignRepository.findByClientId(clientId).map { toCampaignView(it) }

    /**
     * Detail view with rollup: session count, order-state buckets, and discrepancy-line count
     * across every session under the campaign. At most two grouped/aggregate queries
     * ([CountOrderRepository.rollupStateCounts], [CountLineRepository.countDiscrepancies]) plus
     * one plain count ([CountSessionRepository.countByCampaign]) -- no N+1 over sessions/orders.
     */
    fun getCampaign(id: Long, clientId: Long): CountCampaignRollupView {
        val campaign = loadCampaign(id, clientId)
        val sessions = sessionRepository.countByCampaign(id)
        val stateCounts = orderRepository.rollupStateCounts(id)
        val ordersByState = OrdersByStateView(
            generated = stateCounts[CountOrderState.GENERATED.code] ?: 0,
            counted = stateCounts[CountOrderState.COUNTED.code] ?: 0,
            finished = stateCounts[CountOrderState.FINISHED.code] ?: 0,
            cancelled = stateCounts[CountOrderState.CANCELLED.code] ?: 0,
        )
        val discrepancyLines = countLineRepository.countDiscrepancies(id)
        return CountCampaignRollupView(
            id = campaign.id!!,
            campaignNumber = campaign.campaignNumber,
            name = campaign.name,
            type = campaign.type,
            state = campaign.state,
            started = campaign.started,
            ended = campaign.ended,
            sessions = sessions,
            ordersByState = ordersByState,
            discrepancyLines = discrepancyLines,
        )
    }

    /**
     * Closes [id]: refuses (409) while any child [com.karyo.stocktaking.domain.model.CountSession]
     * is still OPEN, else advances the campaign to CLOSED and stamps [CountCampaign.ended]. A
     * session-less campaign (no sessions ever started under it) closes freely -- an empty
     * "all sessions OPEN" set is vacuously true.
     */
    @Transactional
    fun closeCampaign(id: Long, clientId: Long): CountCampaignView {
        val campaign = loadCampaign(id, clientId)
        val openSessions = sessionRepository.findByCampaign(id).any { it.state == CountSessionState.OPEN.code }
        if (openSessions) {
            throw StocktakingException.InvalidState("campaign $id still has an OPEN session")
        }
        if (!CountCampaignState.fromCode(campaign.state).canAdvanceTo(CountCampaignState.CLOSED)) {
            throw StocktakingException.InvalidState("campaign $id cannot advance from ${campaign.state} to CLOSED")
        }
        campaign.state = CountCampaignState.CLOSED.code
        campaign.ended = Instant.now()
        return toCampaignView(campaign)
    }

    private fun loadCampaign(id: Long, clientId: Long): CountCampaign =
        campaignRepository.findByIdAndClient(id, clientId)
            ?: throw StocktakingException.NotFound("CountCampaign", id)

    private fun validateCampaignType(type: String?): CountType {
        if (type == null) return CountType.CYCLE
        return runCatching { CountType.valueOf(type) }.getOrElse {
            throw StocktakingException.InvalidState("unknown campaign type: $type")
        }
    }

    private fun toCampaignView(campaign: CountCampaign): CountCampaignView =
        CountCampaignView(
            id = campaign.id!!,
            campaignNumber = campaign.campaignNumber,
            name = campaign.name,
            type = campaign.type,
            state = campaign.state,
            started = campaign.started,
            ended = campaign.ended,
        )

    companion object {
        /** count_campaigns.campaign_number is VARCHAR(40). */
        private const val MAX_NUMBER_LENGTH = 40
    }
}

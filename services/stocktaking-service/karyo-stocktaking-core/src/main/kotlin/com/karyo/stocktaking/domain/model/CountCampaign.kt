package com.karyo.stocktaking.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.stocktaking.vo.CountCampaignState
import com.karyo.stocktaking.vo.CountType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * A grouping of [CountSession]s above the session level (St1).
 *
 * **Deliberate improvement over legacy myWMS:** legacy's count-campaign grouping was write-only
 * — sessions could be tagged with a campaign, but nothing ever read it back, advanced it, or
 * blocked on it. Karyo's [CountCampaign] is a real lifecycle: a forward-only
 * `OPEN(100) -> CLOSED(700)` state machine ([CountCampaignState.canAdvanceTo]) with a close-out
 * guard (`POST /count-campaigns/{id}/close` 409s while any child session is still OPEN). This is
 * new behavior — the close guard exceeds legacy by design.
 *
 * [type] is validated against [CountType] (`CYCLE` default, `END_OF_PERIOD` the only other value
 * today). Since St5 the campaign/session type rule is a **biconditional type match**, enforced by
 * [com.karyo.stocktaking.service.StocktakingService]'s `requireCampaignAcceptsStart`: a session
 * may only be filed under a campaign whose [type] equals the session's own resolved [CountType]
 * (`campaign.type != countType.name` → 409). Both directions are refused — a `CYCLE` count cannot
 * be filed under an `END_OF_PERIOD` campaign, and an `END_OF_PERIOD` full inventory cannot be
 * filed under a `CYCLE` campaign. The pre-St5 rule (accept only a `CYCLE` campaign, whatever the
 * session was) no longer applies.
 */
@Entity
@Table(name = "count_campaigns")
class CountCampaign : TenantEntity() {

    @Column(name = "campaign_number", nullable = false, length = 40)
    lateinit var campaignNumber: String

    @Column(nullable = false, length = 100)
    lateinit var name: String

    @Column(nullable = false, length = 20)
    var type: String = CountType.CYCLE.name

    @Column(nullable = false)
    var state: Int = CountCampaignState.OPEN.code

    @Column
    var started: Instant? = null

    @Column
    var ended: Instant? = null
}

package com.karyo.stocktaking.domain.model

import com.karyo.common.domain.TenantEntity
import com.karyo.stocktaking.vo.CountSessionState
import com.karyo.stocktaking.vo.CountType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "count_sessions")
class CountSession : TenantEntity() {

    @Column(name = "session_number", nullable = false, length = 40)
    lateinit var sessionNumber: String

    @Column(nullable = false, length = 20)
    var type: String = CountType.CYCLE.name

    @Column(nullable = false)
    var state: Int = CountSessionState.OPEN.code

    /** Owning [CountCampaign], if this session was started under one. Nullable — a plain
     *  `startCount` with no `campaignId` stays a legal, uncampaigned session (also how the
     *  `karyo-demo` generator writes sessions today, unchanged). */
    @Column(name = "campaign_id")
    var campaignId: Long? = null

    @Column
    var started: Instant? = null

    @Column
    var ended: Instant? = null
}

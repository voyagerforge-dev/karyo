package com.karyo.stocktaking.repository

import com.karyo.stocktaking.domain.model.CountCampaign
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CountCampaignRepository : PanacheRepository<CountCampaign> {

    fun findByClientId(clientId: Long): List<CountCampaign> = list("clientId", clientId)

    fun findByIdAndClient(id: Long, clientId: Long): CountCampaign? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()
}

package com.karyo.reporting.repository

import com.karyo.reporting.domain.model.ReportDefinition
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ReportDefinitionRepository : PanacheRepository<ReportDefinition> {

    fun findByClient(clientId: Long): List<ReportDefinition> =
        list("clientId = ?1 order by created desc", clientId)

    fun findByIdAndClient(id: Long, clientId: Long): ReportDefinition? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()
}

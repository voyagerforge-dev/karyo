package com.karyo.work.repository

import com.karyo.work.domain.model.WorkGroup
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class WorkGroupRepository : PanacheRepository<WorkGroup> {
    fun findByClient(clientId: Long): List<WorkGroup> = list("clientId", clientId)
    fun countByClient(clientId: Long): Long = count("clientId", clientId)
    fun findByIdAndClient(id: Long, clientId: Long): WorkGroup? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()
}

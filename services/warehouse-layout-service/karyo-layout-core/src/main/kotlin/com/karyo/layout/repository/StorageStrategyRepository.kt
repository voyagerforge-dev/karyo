package com.karyo.layout.repository

import com.karyo.layout.domain.model.StorageStrategy
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StorageStrategyRepository : PanacheRepository<StorageStrategy> {
    fun findByName(name: String, clientId: Long): StorageStrategy? =
        find("name = ?1 and clientId = ?2", name, clientId).firstResult()

    fun findByClientId(clientId: Long): List<StorageStrategy> =
        list("clientId", clientId)
}

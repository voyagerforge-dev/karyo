package com.karyo.product.repository

import com.karyo.product.domain.model.ItemData
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ItemDataRepository : PanacheRepository<ItemData> {
    fun findByNumber(number: String, clientId: Long): ItemData? =
        find("number = ?1 and clientId = ?2", number, clientId).firstResult()

    fun findByClientId(clientId: Long): List<ItemData> =
        list("clientId", clientId)

    fun findByIds(ids: Collection<Long>): List<ItemData> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)
}

package com.karyo.layout.repository

import com.karyo.layout.domain.model.ItemDataArea
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ItemDataAreaRepository : PanacheRepository<ItemDataArea> {
    fun findByClientId(clientId: Long): List<ItemDataArea> = list("clientId", clientId)

    fun findByArea(storageAreaId: Long, clientId: Long): List<ItemDataArea> =
        list("storageArea.id = ?1 and clientId = ?2", storageAreaId, clientId)

    fun findByItemAndArea(itemDataId: Long, storageAreaId: Long): ItemDataArea? =
        find("itemDataId = ?1 and storageArea.id = ?2", itemDataId, storageAreaId).firstResult()

    /** Produced interface (Task 1, consumed by Tasks 3/5). */
    fun findByItemAndAreas(itemDataId: Long, areaIds: List<Long>): List<ItemDataArea> =
        if (areaIds.isEmpty()) emptyList() else list("itemDataId = ?1 and storageArea.id in ?2", itemDataId, areaIds)

    fun countByArea(areaId: Long): Long = count("storageArea.id", areaId)
}

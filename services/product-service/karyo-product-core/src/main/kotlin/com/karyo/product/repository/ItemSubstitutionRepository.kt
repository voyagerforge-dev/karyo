package com.karyo.product.repository

import com.karyo.product.domain.model.ItemSubstitution
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ItemSubstitutionRepository : PanacheRepository<ItemSubstitution> {

    fun findByPrimary(itemDataId: Long, clientId: Long): List<ItemSubstitution> =
        list("itemDataId = ?1 and clientId = ?2 order by priority asc", itemDataId, clientId)

    fun findActiveByPrimary(itemDataId: Long, clientId: Long): List<ItemSubstitution> =
        list("itemDataId = ?1 and clientId = ?2 and active = true order by priority asc", itemDataId, clientId)

    fun findDuplicate(itemDataId: Long, substituteItemDataId: Long, clientId: Long): ItemSubstitution? =
        find("itemDataId = ?1 and substituteItemDataId = ?2 and clientId = ?3", itemDataId, substituteItemDataId, clientId)
            .firstResult()
}

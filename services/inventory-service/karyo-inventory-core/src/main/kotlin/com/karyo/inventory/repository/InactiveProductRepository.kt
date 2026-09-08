package com.karyo.inventory.repository

import com.karyo.inventory.domain.model.InactiveProduct
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class InactiveProductRepository : PanacheRepository<InactiveProduct> {
    fun isInactive(itemDataId: Long, clientId: Long): Boolean =
        count("itemDataId = ?1 and clientId = ?2", itemDataId, clientId) > 0

    fun findByItemAndClient(itemDataId: Long, clientId: Long): InactiveProduct? =
        find("itemDataId = ?1 and clientId = ?2", itemDataId, clientId).firstResult()
}

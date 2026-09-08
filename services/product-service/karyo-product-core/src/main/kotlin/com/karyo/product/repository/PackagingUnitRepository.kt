package com.karyo.product.repository

import com.karyo.product.domain.model.PackagingUnit
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PackagingUnitRepository : PanacheRepository<PackagingUnit> {
    fun findByIdAndItemData(id: Long, itemDataId: Long): PackagingUnit? =
        find("id = ?1 and itemData.id = ?2", id, itemDataId).firstResult()
}

package com.karyo.product.repository

import com.karyo.product.domain.model.ItemUnit
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ItemUnitRepository : PanacheRepository<ItemUnit> {
    fun findByName(name: String): ItemUnit? = find("name", name).firstResult()
}

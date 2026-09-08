package com.karyo.inventory.repository

import com.karyo.inventory.domain.model.UnitLoadType
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class UnitLoadTypeRepository : PanacheRepository<UnitLoadType> {
    fun findByName(name: String): UnitLoadType? = find("name", name).firstResult()
}

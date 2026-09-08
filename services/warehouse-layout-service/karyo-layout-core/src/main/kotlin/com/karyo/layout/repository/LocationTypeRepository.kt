package com.karyo.layout.repository

import com.karyo.layout.domain.model.LocationType
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class LocationTypeRepository : PanacheRepository<LocationType> {
    fun findByName(name: String): LocationType? = find("name", name).firstResult()
}

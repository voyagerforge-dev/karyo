package com.karyo.layout.repository

import com.karyo.layout.domain.model.Area
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AreaRepository : PanacheRepository<Area> {
    fun findByName(name: String): Area? = find("name", name).firstResult()
}

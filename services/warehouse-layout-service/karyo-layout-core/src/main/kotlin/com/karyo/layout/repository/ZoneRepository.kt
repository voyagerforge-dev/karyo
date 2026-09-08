package com.karyo.layout.repository

import com.karyo.layout.domain.model.Zone
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ZoneRepository : PanacheRepository<Zone> {
    fun findByName(name: String): Zone? = find("name", name).firstResult()
}

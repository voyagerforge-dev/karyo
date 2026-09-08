package com.karyo.layout.repository

import com.karyo.layout.domain.model.LocationCluster
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class LocationClusterRepository : PanacheRepository<LocationCluster> {
    fun findByName(name: String): LocationCluster? = find("name", name).firstResult()
}

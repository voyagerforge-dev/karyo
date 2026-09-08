package com.karyo.orders.repository

import com.karyo.orders.domain.model.OrderStrategy
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class OrderStrategyRepository : PanacheRepository<OrderStrategy> {

    fun findByName(name: String): OrderStrategy? =
        find("name", name).firstResult()
}

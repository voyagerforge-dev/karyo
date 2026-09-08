package com.karyo.product.repository

import com.karyo.product.domain.model.ItemDataNumber
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ItemDataNumberRepository : PanacheRepository<ItemDataNumber> {
    fun findByNumber(number: String): List<ItemDataNumber> =
        list("number", number)
}

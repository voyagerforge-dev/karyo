package com.karyo.product.service

import com.karyo.product.domain.model.ItemUnit
import com.karyo.product.dto.CreateItemUnitRequest
import com.karyo.product.dto.ItemUnitResponse
import com.karyo.product.exception.ProductException
import com.karyo.product.repository.ItemUnitRepository
import com.karyo.product.vo.ItemUnitType
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class ItemUnitService(
    private val itemUnitRepository: ItemUnitRepository,
) {

    @CacheResult(cacheName = "item-units")
    fun listAll(): List<ItemUnitResponse> {
        return itemUnitRepository.listAll().map { toResponse(it) }
    }

    fun findById(id: Long): ItemUnit? {
        return itemUnitRepository.findById(id)
    }

    @Transactional
    fun createUnit(request: CreateItemUnitRequest): ItemUnitResponse {
        // Validate uniqueness
        itemUnitRepository.findByName(request.name)?.let {
            throw ProductException.InvalidConfiguration("Item unit '${request.name}' already exists")
        }

        val unitType = try {
            ItemUnitType.valueOf(request.unitType)
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            throw ProductException.InvalidConfiguration(
                "Invalid unitType: ${request.unitType}. Valid values: ${ItemUnitType.entries.joinToString()}"
            )
        }

        val entity = ItemUnit().apply {
            this.name = request.name
            this.unitType = unitType
        }
        itemUnitRepository.persist(entity)
        invalidateCache()
        return toResponse(entity)
    }

    @CacheInvalidate(cacheName = "item-units")
    fun invalidateCache() {
        // Cache invalidation handled by annotation
    }

    private fun toResponse(entity: ItemUnit): ItemUnitResponse {
        return ItemUnitResponse(
            id = entity.id!!,
            name = entity.name,
            unitType = entity.unitType.name,
        )
    }
}

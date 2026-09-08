package com.karyo.product.service

import com.karyo.product.dto.ProductResponse
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.spi.ProductLookup
import com.karyo.product.spi.ProductMeasures
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process implementation of [ProductLookup], scoped to the current tenant.
 * Returns null (instead of throwing) when the product does not exist or belongs to
 * another tenant, mirroring the former REST client's 404 -> null behavior.
 */
@ApplicationScoped
class DefaultProductLookup(
    private val itemDataRepository: ItemDataRepository,
    private val productService: ProductService,
    private val tenantContext: TenantContext,
) : ProductLookup {

    override fun findById(id: Long): ProductResponse? {
        val entity = itemDataRepository.findById(id) ?: return null
        if (!tenantContext.readScope().permits(entity.clientId)) {
            return null
        }
        return productService.toProductResponse(entity)
    }

    override fun findNamesByIds(ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val scope = tenantContext.readScope()
        return itemDataRepository.findByIds(ids)
            .filter { scope.permits(it.clientId) }
            .associate { it.id!! to it.name }
    }

    override fun findNumbersByIds(ids: Set<Long>, clientId: Long): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return itemDataRepository.findByIds(ids)
            .filter { it.clientId == clientId }
            .associate { it.id!! to it.number }
    }

    override fun findMeasuresByIds(ids: Set<Long>): Map<Long, ProductMeasures> {
        if (ids.isEmpty()) return emptyMap()
        val scope = tenantContext.readScope()
        return itemDataRepository.findByIds(ids)
            .filter { scope.permits(it.clientId) }
            .associate { it.id!! to ProductMeasures(it.weight, it.volume) }
    }

    override fun findMeasuresByIds(ids: Set<Long>, clientId: Long): Map<Long, ProductMeasures> {
        if (ids.isEmpty()) return emptyMap()
        return itemDataRepository.findByIds(ids)
            .filter { it.clientId == clientId }
            .associate { it.id!! to ProductMeasures(it.weight, it.volume) }
    }
}

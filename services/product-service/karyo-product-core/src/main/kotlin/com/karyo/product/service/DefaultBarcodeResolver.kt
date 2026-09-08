package com.karyo.product.service

import com.karyo.product.domain.model.ItemData
import com.karyo.product.dto.*
import com.karyo.product.repository.ItemDataNumberRepository
import com.karyo.product.repository.ItemDataRepository
import com.karyo.product.spi.BarcodeResolver
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default BarcodeResolver SPI implementation.
 * Two-step lookup: item_data_numbers.number first, then item_data.number (SKU) fallback.
 * Returns products regardless of state (active or inactive) per CONTEXT.md.
 */
@ApplicationScoped
class DefaultBarcodeResolver(
    private val numberRepo: ItemDataNumberRepository,
    private val itemDataRepo: ItemDataRepository,
    private val productService: ProductService,
) : BarcodeResolver {

    @CacheResult(cacheName = "products-by-barcode")
    override fun resolve(@CacheKey barcode: String, @CacheKey clientId: Long): ProductResponse? {
        // Step 1: Search item_data_numbers.number first
        val numbers = numberRepo.findByNumber(barcode)
        val match = numbers.firstOrNull { it.itemData.clientId == clientId }
        if (match != null) {
            return productService.toProductResponse(match.itemData)
        }

        // Step 2: Fall back to item_data.number (SKU)
        val product = itemDataRepo.findByNumber(barcode, clientId)
        return product?.let { productService.toProductResponse(it) }
    }
}

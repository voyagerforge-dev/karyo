package com.karyo.product.spi

/**
 * In-process packaging-unit lookup contract. Implemented by product-core and consumed by
 * inventory-core to validate a caller-supplied `packagingUnitId` at stock creation (A5) --
 * both that it exists and that it belongs to the item being received -- instead of storing
 * it blind, matching the [ProductLookup] pattern.
 */
interface PackagingUnitLookup {
    fun findById(id: Long): PackagingUnitInfo?
}

/**
 * Minimal identity/ownership projection of a PackagingUnit for cross-module validation.
 * Deliberately not tenant-scoped on its own: the only caller compares [itemDataId] against
 * an id that is already within its own write scope, so a cross-tenant packagingUnitId simply
 * fails that comparison rather than needing a separate tenant check here.
 */
data class PackagingUnitInfo(val id: Long, val name: String, val itemDataId: Long)

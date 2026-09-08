package com.karyo.demo.gen

/** One demo SKU: the persisted [com.karyo.product.domain.model.ItemData] id + its number (e.g. `DEMO-SKU-01`). */
data class SkuRef(val itemDataId: Long, val number: String)

/** One demo storage location: id, name, owning zone id, and its distinct slotting `orderIndex`. */
data class LocationRef(val id: Long, val name: String, val zoneId: Long, val orderIndex: Int)

/**
 * Handle returned by [CatalogGenerator.generate] — the current-state catalog (locations, SKUs,
 * the single demo unit-load type, and the 4 zone ids) that later generators (stock, history, ...)
 * consume to place inventory and build activity.
 */
data class CatalogRefs(
    val skus: List<SkuRef>,
    val locations: List<LocationRef>,
    val unitLoadTypeId: Long,
    val zoneIds: List<Long>,
)

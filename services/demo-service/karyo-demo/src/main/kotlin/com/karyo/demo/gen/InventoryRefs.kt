package com.karyo.demo.gen

/**
 * One demo stock line: the persisted [com.karyo.inventory.domain.model.StockUnit] id, its SKU
 * (`itemDataId`/`number`), the [com.karyo.layout.domain.model.StorageLocation] it sits on, and
 * the id of the containing [com.karyo.inventory.domain.model.UnitLoad].
 */
data class StockRef(
    val stockUnitId: Long,
    val itemDataId: Long,
    val number: String,
    val locationId: Long,
    val unitLoadId: Long,
)

/**
 * Handle returned by [InventoryGenerator.generate] — the current-state on-hand stock, plus the
 * SKU numbers seeded with a near-expiry lot (drives the expiry-risk monitor) and the one SKU
 * seeded with a fix-assignment min set above its actual on-hand total (drives the
 * bin-below-reorder monitor).
 */
data class InventoryRefs(
    val stock: List<StockRef>,
    val expiringSkus: List<String>,
    val belowMinSku: String,
)

package com.karyo.fulfillment.spi

/**
 * Batch zone resolution for wave batch grouping. Default (built into fulfillment-core when no
 * other bean provides it): every id maps to null -> single UNZONED batch group. The wave module
 * registers the real implementation.
 */
interface PickZoneLookup {
    fun zonesByStockUnitIds(stockUnitIds: Set<Long>, clientId: Long): Map<Long, String?>
}

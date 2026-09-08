package com.karyo.inventory.api.spi

import com.karyo.inventory.api.vo.StockSelectionRequest

/**
 * SPI for custom stock selection filters.
 * Implementations filter/reorder candidate stocks before the core algorithm picks.
 * Deploy as @Alternative @Priority in a client extension JAR.
 */
interface StockSelectionFilter {
    /** Filter or reorder candidates. Return a subset or reordered list. */
    fun filter(candidateStockUnitIds: List<Long>, request: StockSelectionRequest): List<Long>

    /** Lower priority = earlier in filter chain. Default implementations use 1000. */
    fun priority(): Int = 1000
}

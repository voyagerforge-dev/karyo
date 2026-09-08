package com.karyo.inventory.ext.example

import com.karyo.inventory.api.spi.StockSelectionFilter
import com.karyo.inventory.api.vo.StockSelectionRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Historical pass-through template, not hazmat enforcement.
 * Deliberately lacks a CDI activation priority so it stays inactive even in the augmented
 * example build. Use [HeldLotStockFilter] and the public implementer cookbook instead.
 */
@Alternative
@ApplicationScoped
class HazmatStockFilter : StockSelectionFilter {

    override fun filter(candidateStockUnitIds: List<Long>, request: StockSelectionRequest): List<Long> {
        // Example: in a real implementation, this would check itemData.tradeGroup == "HAZMAT"
        // and filter candidates to hazmat-zone locations only.
        return candidateStockUnitIds
    }

    override fun priority(): Int = 500
}

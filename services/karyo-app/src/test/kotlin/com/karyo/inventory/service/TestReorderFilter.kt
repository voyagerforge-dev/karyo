package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockSelectionFilter
import com.karyo.inventory.api.vo.StockSelectionRequest
import jakarta.enterprise.context.ApplicationScoped

/**
 * Test-only [StockSelectionFilter]. Inert (pass-through) unless [reverse] is set, so it never
 * disturbs other tests; priority()=1 sorts it first. Used to prove the core honors filter
 * *reordering* (previously the core re-imposed FIFO and discarded reorder).
 */
@ApplicationScoped
class TestReorderFilter : StockSelectionFilter {
    override fun filter(candidateStockUnitIds: List<Long>, request: StockSelectionRequest): List<Long> =
        if (reverse) candidateStockUnitIds.reversed() else candidateStockUnitIds

    override fun priority(): Int = 1

    companion object {
        @JvmStatic
        var reverse: Boolean = false
    }
}

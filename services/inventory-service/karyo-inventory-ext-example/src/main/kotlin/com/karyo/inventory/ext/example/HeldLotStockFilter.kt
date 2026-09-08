package com.karyo.inventory.ext.example

import com.karyo.inventory.api.spi.StockSelectionFilter
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.vo.StockSelectionRequest
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Executable cookbook example: exclude synthetic lots starting with [HELD_LOT_PREFIX].
 * This is not a quarantine system or hazmat enforcement. Install this example only in a
 * disposable demonstration build, using the public implementer guide's augmentation path.
 *
 * Only existing candidates may survive; their FIFO order is preserved. The batched lookup
 * uses the request's goods owner explicitly, so it also works for non-request callers.
 * Missing rows fail closed. Ordinary stock eligibility and reservation rules still run in core.
 */
@Alternative
@Priority(1000)
@ApplicationScoped
class HeldLotStockFilter(private val stocks: StockUnitLookup) : StockSelectionFilter {
    override fun filter(candidateStockUnitIds: List<Long>, request: StockSelectionRequest): List<Long> {
        if (candidateStockUnitIds.isEmpty()) return emptyList()
        val allowed = stocks.findByIds(candidateStockUnitIds.toSet(), request.clientId)
            .filter { it.itemDataId == request.itemDataId && it.lotNumber?.startsWith(HELD_LOT_PREFIX) != true }
            .mapTo(mutableSetOf()) { it.id }
        return candidateStockUnitIds.filter { it in allowed }
    }

    override fun priority(): Int = 500

    companion object {
        const val HELD_LOT_PREFIX = "EXAMPLE-HOLD-"
    }
}

package com.karyo.inventory.service

import com.karyo.inventory.api.spi.StockSummary
import com.karyo.inventory.api.spi.StockSummaryLookup
import com.karyo.inventory.repository.StockUnitRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Default in-process implementation of [StockSummaryLookup] (R12a, replenishment sprint
 * Task 5). Explicit `clientId`, no ambient `TenantContext` read — see the interface KDoc.
 */
@ApplicationScoped
class DefaultStockSummaryLookup(
    private val stockUnitRepository: StockUnitRepository,
) : StockSummaryLookup {

    override fun summaryInLocations(itemDataId: Long, clientId: Long, locationIds: Set<Long>): StockSummary {
        if (locationIds.isEmpty()) return StockSummary(BigDecimal.ZERO, 0)
        val (total, count) = stockUnitRepository.summaryInLocations(itemDataId, clientId, locationIds)
        return StockSummary(total, count.toInt())
    }
}

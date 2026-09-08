package com.karyo.stocktaking.service

import com.karyo.inventory.api.spi.PurgeBlockerLookup
import com.karyo.stocktaking.repository.CountLineRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Row 18, stocktaking half of [PurgeBlockerLookup]: a `count_lines` row under an OPEN count
 * session blocks both the stock unit and the unit load it references. A closed session's lines
 * are historical (see [PurgeBlockerLookup]'s KDoc), so they never block.
 */
@ApplicationScoped
class CountPurgeBlockerLookup(
    private val repository: CountLineRepository,
) : PurgeBlockerLookup {

    override fun blockedStockUnitIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        repository.findOpenSessionStockUnitIds(candidateIds, clientId)

    override fun blockedUnitLoadIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        repository.findOpenSessionUnitLoadIds(candidateIds, clientId)
}

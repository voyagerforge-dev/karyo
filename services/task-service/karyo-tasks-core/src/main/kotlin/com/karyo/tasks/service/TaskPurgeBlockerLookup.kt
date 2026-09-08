package com.karyo.tasks.service

import com.karyo.inventory.api.spi.PurgeBlockerLookup
import com.karyo.tasks.repository.TransportOrderRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Row 18, tasks half of [PurgeBlockerLookup]: a non-terminal (not FINISHED/CANCELED) transport
 * order blocks both the stock unit it is moving (`sourceStockUnitId`) and the unit load it is
 * moving (`unitLoadId`). A finished or canceled transport order is a record of what happened,
 * not a live claim, so it never blocks -- see [PurgeBlockerLookup]'s KDoc.
 */
@ApplicationScoped
class TaskPurgeBlockerLookup(
    private val repository: TransportOrderRepository,
) : PurgeBlockerLookup {

    override fun blockedStockUnitIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        repository.findOpenSourceStockUnitIds(candidateIds, clientId)

    override fun blockedUnitLoadIds(candidateIds: Collection<Long>, clientId: Long): Set<Long> =
        repository.findOpenUnitLoadIds(candidateIds, clientId)
}

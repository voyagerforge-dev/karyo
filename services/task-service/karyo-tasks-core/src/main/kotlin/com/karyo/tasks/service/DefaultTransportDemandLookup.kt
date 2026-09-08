package com.karyo.tasks.service

import com.karyo.layout.spi.TransportDemandLookup
import com.karyo.layout.spi.TransportDemandRef
import com.karyo.tasks.repository.TransportOrderRepository
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default implementation of [TransportDemandLookup], repo-direct, same rationale as
 * [DefaultTransportOrderPort]'s `hasOpenAreaReplenishment`/`openReplenishmentUnitLoadIds`:
 * a trivial batched read with no business logic, not worth a [TaskService] passthrough.
 *
 * Row :1614: the target location id is coalesced -- `destinationLocationId` when set, else
 * `suggestedLocationId` (the open PUTAWAY/TRANSFER shape) -- and a row with neither is
 * dropped. `transportOrderId` is always the order's own id, carried for
 * [com.karyo.layout.service.OccupancyMixReader]'s self-exclusion guard.
 */
@ApplicationScoped
class DefaultTransportDemandLookup(
    private val transportOrderRepository: TransportOrderRepository,
) : TransportDemandLookup {
    override fun openDemandByLocationIds(destinationLocationIds: Set<Long>): List<TransportDemandRef> =
        transportOrderRepository.findOpenDemandByDestinationIds(destinationLocationIds)
            .mapNotNull { to ->
                val locationId = to.destinationLocationId ?: to.suggestedLocationId
                locationId?.let { TransportDemandRef(it, to.clientId, to.itemDataId, to.id!!) }
            }
}

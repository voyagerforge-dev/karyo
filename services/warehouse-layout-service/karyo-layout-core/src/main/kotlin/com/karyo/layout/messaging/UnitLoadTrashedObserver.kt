package com.karyo.layout.messaging

import com.karyo.inventory.api.event.UnitLoadTrashedEvent
import com.karyo.layout.service.LocationService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * Observes [UnitLoadTrashedEvent] fired in-process by the inventory module at both UL-terminal
 * choke points — hard delete ([com.karyo.inventory.service.UnitLoadService.delete]) and the
 * stocktaking emptied-UL soft flip ([com.karyo.inventory.service.DefaultStockCountingPort.applyCount]).
 *
 * Releases this unit load's share of `StorageLocation.allocation` (-100), the sibling of
 * [UnitLoadTransferredObserver]'s occupancy bookkeeping — a terminated UL no longer occupies its
 * slot, but nothing previously told the location that. Runs synchronously and joins the caller's
 * transaction (REQUIRED), same as [UnitLoadTransferredObserver].
 */
@ApplicationScoped
class UnitLoadTrashedObserver(
    private val locationService: LocationService,
) {
    private val log = Logger.getLogger(UnitLoadTrashedObserver::class.java)

    @Transactional
    fun onTrashed(@Observes event: UnitLoadTrashedEvent) {
        if (event.locationId > 0) {
            locationService.updateAllocation(
                event.locationId,
                UnitLoadTransferredObserver.ALLOCATION_PER_UL.negate(),
            )
        }

        log.info("Processed UnitLoadTrashed: UL ${event.labelId} released location ${event.locationId}")
    }
}

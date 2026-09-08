package com.karyo.layout.messaging

import com.karyo.inventory.api.event.UnitLoadRevivedEvent
import com.karyo.layout.service.LocationService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.jboss.logging.Logger

/**
 * Bulk Allocation Sprint C: the mirror of [UnitLoadTrashedObserver]. Observes
 * [UnitLoadRevivedEvent] fired in-process by the inventory module when a unit load that was
 * tombstoned DELETABLE only because it went empty is brought back to receive returned goods
 * (`StockPicker.reviveDrainedContainer`, driven by a consolidation-shipment cancel).
 *
 * Re-takes this unit load's share of `StorageLocation.allocation` (+100), exactly the amount
 * [UnitLoadTrashedObserver] released when the unit load was tombstoned. Same magnitude, same
 * constant ([UnitLoadTransferredObserver.ALLOCATION_PER_UL]), opposite sign, so a trash/revive
 * round trip is allocation-neutral. Runs synchronously and joins the caller's transaction
 * (REQUIRED), same as both siblings.
 */
@ApplicationScoped
class UnitLoadRevivedObserver(
    private val locationService: LocationService,
) {
    private val log = Logger.getLogger(UnitLoadRevivedObserver::class.java)

    @Transactional
    fun onRevived(@Observes event: UnitLoadRevivedEvent) {
        if (event.locationId > 0) {
            locationService.updateAllocation(
                event.locationId,
                UnitLoadTransferredObserver.ALLOCATION_PER_UL,
            )
        }

        log.info("Processed UnitLoadRevived: UL ${event.labelId} re-took location ${event.locationId}")
    }
}

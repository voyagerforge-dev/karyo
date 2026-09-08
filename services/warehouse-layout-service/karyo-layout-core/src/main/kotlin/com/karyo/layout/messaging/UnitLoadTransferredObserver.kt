package com.karyo.layout.messaging

import com.karyo.inventory.api.event.UnitLoadTransferredEvent
import com.karyo.layout.service.LocationService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal

/**
 * Observes UnitLoadTransferredEvent fired in-process by the inventory module.
 * Updates location allocation: -100 at source, +100 at destination.
 * Each unit load transfer uses a fixed allocation of 100% per UL for v1.
 * Runs synchronously and joins the caller's transaction (REQUIRED), replacing
 * the former Kafka consumer.
 */
@ApplicationScoped
class UnitLoadTransferredObserver(
    private val locationService: LocationService,
) {
    private val log = Logger.getLogger(UnitLoadTransferredObserver::class.java)

    companion object {
        val ALLOCATION_PER_UL: BigDecimal = BigDecimal("100")
    }

    @Transactional
    fun onUnitLoadTransferred(@Observes event: UnitLoadTransferredEvent) {
        // Decrease allocation at source
        if (event.fromLocationId > 0) {
            locationService.updateAllocation(
                event.fromLocationId,
                ALLOCATION_PER_UL.negate(),
            )
        }

        // Increase allocation at destination
        if (event.toLocationId > 0) {
            locationService.updateAllocation(
                event.toLocationId,
                ALLOCATION_PER_UL,
            )
        }

        log.info("Processed UnitLoadTransferred: UL ${event.labelId} from ${event.fromLocationName} to ${event.toLocationName}")
    }
}

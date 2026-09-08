package com.karyo.inventory.service

import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.event.StockUnitAmountChangedEvent
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.domain.model.StockUnit
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Single publish point for a reservation-only `StockUnitAmountChangedEvent`: [su]'s physical
 * `amount` is untouched, only `reservedAmount` moved, so `oldAmount`/`newAmount` report no
 * movement and `changeAmount` is always ZERO. Extracted here so `StockService.reserveStock`,
 * `StockService.releaseReservation`, and `ReservationTransferService.transferReservation` share
 * one publish block instead of three independent inline copies of the same event shape --
 * `StockService` is at detekt's 25-function-per-class ceiling (Task 1), so the shared block lives
 * on its own bean rather than as a new private helper there.
 */
@ApplicationScoped
class StockAmountEventPublisher(
    private val outboxService: OutboxService,
) {
    fun publish(su: StockUnit, activityCode: String) {
        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "AmountChanged",
            payload = StockUnitAmountChangedEvent(
                stockUnitId = su.id!!,
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                oldAmount = su.amount,
                newAmount = su.amount,
                changeAmount = BigDecimal.ZERO,
                recordType = JournalRecordType.CHANGED.code,
                activityCode = activityCode,
                locationId = su.unitLoad.storageLocationId,
                locationName = su.unitLoad.storageLocationName,
            ),
            tenantId = su.clientId,
        )
    }
}

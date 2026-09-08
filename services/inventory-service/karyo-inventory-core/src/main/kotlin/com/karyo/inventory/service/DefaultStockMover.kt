package com.karyo.inventory.service

import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.api.event.UnitLoadTransferredEvent
import com.karyo.inventory.api.spi.MovedStock
import com.karyo.inventory.api.spi.StockMover
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import java.math.BigDecimal

/**
 * Default in-process [StockMover], scoped to the current tenant. Delegates to
 * [StockService.transferStock], which already carries the same-owner (D1) guard, the
 * aggregate-stocks merge-vs-new-row gate, DELETABLE-on-drain, and journal + outbox writes —
 * the mechanism the pick flow already exercises when it lands stock on a new pallet. Thin
 * wrapper, no business logic of its own, matching [DefaultUnitLoadMover]'s shape —
 * [transferToLocation]'s find-or-create routing (PT17, Task 4) is the one exception, kept here
 * rather than in the tasks module because the repos it scans (`UnitLoadRepository`/
 * `StockUnitRepository`) already live in this package.
 */
@ApplicationScoped
class DefaultStockMover(
    private val stockService: StockService,
    private val unitLoadService: UnitLoadService,
    private val unitLoadRepository: UnitLoadRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val outboxService: OutboxService,
    private val transferredEvent: Event<UnitLoadTransferredEvent>,
    private val tenantContext: TenantContext,
    private val sequenceNumberService: SequenceNumberService,
) : StockMover {

    @Transactional
    override fun transferToUnitLoad(
        stockUnitId: Long,
        targetUnitLoadId: Long,
        amount: BigDecimal,
        activityCode: String,
    ): MovedStock {
        val target = stockService.transferStock(stockUnitId, targetUnitLoadId, amount, activityCode, tenantContext)
        return target.toMovedStock()
    }

    @Transactional
    override fun transferToUnitLoad(
        stockUnitId: Long,
        targetUnitLoadId: Long,
        amount: BigDecimal,
        activityCode: String,
        clientId: Long,
    ): MovedStock {
        val target = stockService.transferStock(
            stockUnitId,
            targetUnitLoadId,
            amount,
            activityCode,
            TenantContext.ownerScoped(clientId, tenantContext.username),
        )
        return target.toMovedStock()
    }

    @Transactional
    override fun transferToLocation(
        stockUnitId: Long,
        locationId: Long,
        locationName: String,
        amount: BigDecimal,
        activityCode: String,
    ): MovedStock {
        val source = stockService.findByIdForWrite(stockUnitId, tenantContext)
        val (targetUl, created) = resolveTargetUnitLoad(source, locationId, locationName)
        val target = stockService.transferStock(stockUnitId, targetUl.id!!, amount, activityCode, tenantContext)
        if (created) {
            fireCreateOnlyTransfer(targetUl, locationId, locationName)
        }
        return target.toMovedStock()
    }

    /**
     * Find-or-create routing described on [StockMover.transferToLocation]'s KDoc: a reusable
     * summable match first, then any same-type unit load at the location, then a brand-new one.
     * [source]'s own unit load is excluded from candidates (transferring onto itself is not a
     * move). Reuse candidates are restricted to ON_STOCK, unlocked unit loads — the same
     * physically-settled window [com.karyo.inventory.api.spi.StockUnitLookup.occupancyByLocationIds]
     * uses — so a mid-receipt (INCOMING) or locked pallet is never picked as a merge target.
     * Returns whether the unit load was freshly created (the two reuse branches return an
     * already-allocated unit load; only the create branch needs [fireCreateOnlyTransfer]).
     */
    private fun resolveTargetUnitLoad(source: StockUnit, locationId: Long, locationName: String): Pair<UnitLoad, Boolean> {
        val candidates = unitLoadRepository.findByStorageLocationId(locationId).filter {
            it.clientId == source.clientId &&
                it.id != source.unitLoad.id &&
                it.state == StockState.ON_STOCK.code &&
                it.lockType == 0
        }
        val summable = candidates.firstOrNull { candidate ->
            candidate.unitLoadType.aggregateStocks &&
                stockUnitRepository.findByUnitLoadId(candidate.id!!).any {
                    it.itemDataId == source.itemDataId && it.lotNumber == source.lotNumber && it.clientId == source.clientId
                }
        }
        if (summable != null) return summable to false
        val sameType = candidates.firstOrNull { it.unitLoadType.id == source.unitLoad.unitLoadType.id }
        if (sameType != null) return sameType to false
        return createUnitLoadAt(source, locationId, locationName) to true
    }

    /**
     * Belt fix (final-gate item 3): a freshly created unit load never went through
     * [UnitLoadService.transferToLocation]/`transferToCarrier`, so the destination location's
     * allocation ([com.karyo.layout.messaging.UnitLoadTransferredObserver]) was never
     * incremented — a partial confirm landing on an empty location silently created a pallet
     * the layout module didn't know occupied the slot. Fires [UnitLoadTransferredEvent] with
     * `fromLocationId = 0`/`fromLocationName = ""` (the observer's `> 0` guards make this
     * one-sided: only the destination's allocation moves, nothing is decremented at a
     * nonexistent "from"), same shape [UnitLoadService.transferToLocation] uses for a real move.
     */
    private fun fireCreateOnlyTransfer(ul: UnitLoad, locationId: Long, locationName: String) {
        val event = UnitLoadTransferredEvent(
            unitLoadId = ul.id!!,
            labelId = ul.labelId,
            fromLocationId = 0,
            fromLocationName = "",
            toLocationId = locationId,
            toLocationName = locationName,
            stockUnitCount = stockUnitRepository.findByUnitLoadId(ul.id!!).size,
        )
        outboxService.publish(
            aggregateType = "UnitLoad",
            aggregateId = ul.id!!,
            eventType = "UnitLoadTransferred",
            payload = event,
            tenantId = ul.clientId,
        )
        transferredEvent.fire(event)
    }

    private fun createUnitLoadAt(source: StockUnit, locationId: Long, locationName: String): UnitLoad =
        unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = source.clientId,
                labelId = generateLabel(source.clientId),
                unitLoadTypeId = source.unitLoad.unitLoadType.id!!,
                storageLocationId = locationId,
                storageLocationName = locationName,
            ),
            tenantContext,
        )

    /** SC17: same `unitload.labelId` sequence `DefaultStockReceiver.generateLabel` uses. */
    private fun generateLabel(clientId: Long): String =
        sequenceNumberService.next("unitload.labelId", "UL", clientId, MAX_LABEL_LENGTH) {
            unitLoadRepository.findByLabelId(it) == null
        }

    private fun StockUnit.toMovedStock() = MovedStock(
        stockUnitId = id!!,
        unitLoadId = unitLoad.id!!,
        unitLoadLabel = unitLoad.labelId,
    )

    companion object {
        /** unit_loads.label_id is VARCHAR(255). */
        private const val MAX_LABEL_LENGTH = 255
    }
}

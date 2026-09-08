package com.karyo.inventory.service

import com.karyo.inventory.api.event.UnitLoadTrashedEvent
import com.karyo.inventory.api.spi.CountableStock
import com.karyo.inventory.api.spi.StockCountingPort
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal

@ApplicationScoped
class DefaultStockCountingPort(
    private val unitLoadRepository: UnitLoadRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val stockService: StockService,
    private val journalService: JournalService,
    private val tenant: TenantContext,
) : StockCountingPort {

    /**
     * Countable stock at [locationId] — soft-deleted stock is EXCLUDED
     * (`state != `[StockState.DELETABLE]).
     *
     * [StockService.deleteStock] only flips `state` to [StockState.DELETABLE]; the row physically
     * survives and [StockUnitRepository.findByUnitLoadId] still returns it. Without this filter a
     * soft-deleted unit would be planned into a fresh count order and re-counted for no reason.
     * [StockService.adjustAmount] now carries its own DELETABLE guard too (defect row 7,
     * 2026-08-02 burndown), so this filter is defense-in-depth, not the only thing standing
     * between a stale count and a resurrection.
     */
    override fun findStockAtLocation(locationId: Long): List<CountableStock> {
        val scope = tenant.readScope()
        return unitLoadRepository.findByStorageLocationId(locationId)
            .filter { scope.permits(it.clientId) }
            .flatMap { ul ->
                stockUnitRepository.findByUnitLoadId(ul.id!!)
                    .filter { scope.permits(it.clientId) }
                    .filter { it.state != StockState.DELETABLE.code }
                    .map { su ->
                        CountableStock(
                            stockUnitId = su.id!!,
                            itemDataId = su.itemDataId,
                            itemDataNumber = su.itemDataNumber,
                            lotNumber = su.lotNumber,
                            serialNumber = su.serialNumber,
                            amount = su.amount,
                            reservedAmount = su.reservedAmount,
                            unitLoadId = ul.id!!,
                            unitLoadLabel = ul.labelId,
                        )
                    }
            }
    }

    @Transactional
    override fun lockForCount(stockUnitIds: List<Long>) {
        stockUnitIds.forEach { stockService.setLock(it, LockType.STOCKTAKING.code, tenant) }
    }

    @Transactional
    override fun releaseCount(stockUnitIds: List<Long>) {
        stockUnitIds.forEach { stockService.setLock(it, LockType.UNLOCKED.code, tenant) }
    }

    /**
     * St4 emptied-UL check: [StockService.deleteStock] only *soft*-deletes the [StockUnit]
     * (flips `state` to [StockState.DELETABLE] and journals) -- it never touches the parent
     * [UnitLoad][com.karyo.inventory.domain.model.UnitLoad] row, and there is no cascade from
     * `stock_units.unit_load_id` (plain `REFERENCES`, no `ON DELETE`). So this branch calls
     * [UnitLoadTerminator.trashIfEmpty] right after [StockService.deleteStock] -- same idiom as
     * before (a state flip, not a physical row removal), just no longer inlined here.
     * [StockService.deleteStock] itself deliberately does NOT make this call (see its KDoc: it
     * would also fire for [DefaultStockReceiver.unreceive], which needs the opposite behavior).
     * A *hard* delete ([com.karyo.inventory.service.UnitLoadService.delete]) is not an option:
     * its guard counts ALL [com.karyo.inventory.repository.StockUnitRepository.findByUnitLoadId] rows regardless
     * of state, and the just-soft-deleted [StockUnit] row(s) still physically reference the UL
     * via that same FK -- a hard delete here would either throw `HasDependents` or violate the
     * FK outright.
     *
     * **The flip also releases the location** (defect row 3, 2026-08-02 burndown): it fires
     * [UnitLoadTrashedEvent], observed synchronously by layout-core's `UnitLoadTrashedObserver`,
     * which releases this UL's `StorageLocation.allocation` (-100) the same way
     * `UnitLoadTransferredObserver` does for a transfer. [UnitLoad.state][com.karyo.inventory.domain.model.UnitLoad.state]
     * is NOT read-only-ever-written, either: `DefaultStockReceiver.validateReusable` reads it
     * (the `REUSABLE_UL_STATES` gate) when deciding whether an existing UL can take new goods
     * receiving on it. The hard-delete path ([com.karyo.inventory.service.UnitLoadService.delete])
     * releases the same allocation at its own choke point, via the same event.
     *
     * Idempotency (task-10, defect-burndown-4, row 14) is now enforced inside
     * [UnitLoadTerminator.trashIfEmpty] itself, and the gap that used to let a re-entry happen at
     * all -- `transferStock`/`transferToLocation` accepting a DELETABLE target UL -- is closed at
     * those choke points (`StockService.unitLoadForWrite`, `UnitLoadService.transferToLocation`),
     * not merely papered over here.
     */
    @Transactional
    override fun applyCount(stockUnitId: Long, countedAmount: BigDecimal, activityCode: String) {
        val su = stockService.adjustAmount(stockUnitId, countedAmount, activityCode, tenant)
        journalService.record(JournalRecordType.COUNTED, su, tenant, activityCode = activityCode)
        if (countedAmount.signum() == 0) {
            val deleted = stockService.deleteStock(stockUnitId, tenant)
            stockService.trashUnitLoadIfEmpty(deleted, tenant, activityCode)
        }
    }

    @Transactional
    override fun recordMatchCounted(stockUnitId: Long, activityCode: String) {
        // Fully implemented here; dedicated tests land in Task 4.
        val su = stockService.findById(stockUnitId, tenant)
        journalService.record(JournalRecordType.COUNTED, su, tenant, activityCode = activityCode)
    }
}

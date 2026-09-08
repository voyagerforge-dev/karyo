package com.karyo.inventory.service

import com.karyo.inventory.api.spi.ReservationRefMover
import com.karyo.inventory.api.spi.ReservationSourceState
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.security.TenantContext
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.exception.InventoryException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.transaction.Transactional
import java.math.BigDecimal

/**
 * Row 13: atomically moves a reserved amount from one stock unit to another, carrying the
 * order-line and open-pick references with it. Public behavioral contract:
 * `docs/functional/inventory-operations.md#reservation-transfer`. The predecessor call site is
 * replenishment stock aggregation, immediately after `transferStock` merges summable stock.
 *
 * A separate collaborator bean rather than a [StockService] method: `StockService` sits at
 * detekt's 25-function-per-class ceiling as of Task 1 (`changePackagingUnit`), so this method
 * and its guards live here instead, reusing [StockService.findByIdForWrite] (already public)
 * for the tenant-scoped write-path lookup rather than reimplementing it.
 *
 * Total: every refusal is checked before the first write, so a refused call leaves both rows
 * exactly as it found them. Over-transfer refuses rather than clamping, matching
 * [StockService.releaseReservation]'s invariant (clamping there was defect I1: it let one order
 * silently consume another order's live reservation on the same unit).
 *
 * defect-burndown-5 (:1485), superseding the old refuse-whole ruling: a source with any STALE
 * reference (a reservation row whose backing pick already went terminal, see
 * [ReservationSourceState.staleCount]) no longer refuses the whole transfer. Instead the operation
 * SPLITS: the requested [amount][transferReservation] must equal the source's total LIVE
 * reservation, folded per SLICE -- a `(lineId, stockUnitId)` pair -- the SAME grouping
 * `OrderService.unhandledRemainders` folds by, because one slice can be backed by more than one
 * bookkeeping row (409 naming the live amount otherwise), and each mover's
 * [ReservationRefMover.moveRefs] repoints-or-splits per slice as ruled in
 * [ReservationRefMover]'s KDoc. The invariant this preserves: the terminal-consumed portion of a
 * slice stays keyed to the stock unit that actually consumed it, FOREVER -- `unhandledRemainders`
 * nets terminal-pick totals against the pick's own, never-moved, `sourceStockUnitId`, so moving
 * that portion away would silently break the netting join, make `cancel()` compute `remainder =
 * reserved - 0` instead of `reserved - terminal`, and release more than was ever live. A stock
 * unit mixing terminal and open pick history can now have its live remainder transferred without
 * waiting for the terminal slice's order to cancel; the invariant is preserved by row surgery
 * instead of by refusal.
 *
 * defect-burndown-5 review finding 3: each mover's [ReservationRefMover.readSourceState] is
 * called exactly ONCE per transfer, up front, and the resulting [ReservationSourceState]s are
 * threaded through both the guard checks and [ReservationRefMover.moveRefs] -- the validated live
 * figure and the actual row surgery are, by construction, the same computation.
 */
@ApplicationScoped
class ReservationTransferService(
    private val stockService: StockService,
    private val journalService: JournalService,
    private val eventPublisher: StockAmountEventPublisher,
    private val refMovers: Instance<ReservationRefMover>,
) {

    @Transactional
    fun transferReservation(fromId: Long, toId: Long, amount: BigDecimal?, tenant: TenantContext): StockUnit {
        if (fromId == toId) {
            throw InventoryException.ValidationFailed("cannot transfer a reservation from a stock unit to itself")
        }
        val from = stockService.findByIdForWrite(fromId, tenant)
        val to = stockService.findByIdForWrite(toId, tenant)
        val moved = amount ?: from.reservedAmount
        val states = refMovers.associateWith { it.readSourceState(fromId, from.clientId) }
        requireTransferable(from, to, moved, states)

        from.reservedAmount = from.reservedAmount.subtract(moved)
        to.reservedAmount = to.reservedAmount.add(moved)
        states.forEach { (mover, state) -> mover.moveRefs(fromId, toId, from.clientId, state) }

        journalReservationTransfer(from, to, moved, tenant)
        eventPublisher.publish(from, ACTIVITY_CODE)
        eventPublisher.publish(to, ACTIVITY_CODE)
        return to
    }

    /**
     * A2's refusal list, checked in order: same owner, same item, a valid source amount, target
     * capacity, target availability (lock + state), and then either the amount-must-equal-live
     * check (a mixed source, defect-burndown-5 :1485) or the partial-with-live-references refusal
     * (a clean source, unchanged). Split into single/double-throw helpers to stay under detekt's
     * `ThrowsCount` budget.
     */
    private fun requireTransferable(
        from: StockUnit,
        to: StockUnit,
        moved: BigDecimal,
        states: Map<ReservationRefMover, ReservationSourceState>,
    ) {
        requireSameOwner(from, to)
        requireSameItem(from, to)
        requireValidSourceAmount(from, moved)
        requireTargetCapacity(to, moved)
        requireTargetAvailable(to)
        val staleCount = states.values.sumOf { it.staleCount }
        if (staleCount > 0) {
            requireLiveAmountMatch(from, moved, states)
        } else {
            requirePartialAllowed(from, moved)
        }
    }

    private fun requireSameOwner(from: StockUnit, to: StockUnit) {
        if (from.clientId != to.clientId) {
            throw InventoryException.CrossOwner(
                "cannot transfer a reservation from stock unit ${from.id} (client ${from.clientId}) to " +
                    "stock unit ${to.id} (client ${to.clientId})",
            )
        }
    }

    private fun requireSameItem(from: StockUnit, to: StockUnit) {
        if (from.itemDataId != to.itemDataId) {
            throw InventoryException.InvalidTarget(
                "target stock unit ${to.id} holds item ${to.itemDataId}; source stock unit ${from.id} holds item " +
                    "${from.itemDataId}",
            )
        }
    }

    private fun requireValidSourceAmount(from: StockUnit, moved: BigDecimal) {
        if (moved <= BigDecimal.ZERO || moved > from.reservedAmount) {
            throw InventoryException.InsufficientStock(from.reservedAmount, moved)
        }
    }

    private fun requireTargetCapacity(to: StockUnit, moved: BigDecimal) {
        if (to.availableAmount < moved) {
            throw InventoryException.InsufficientStock(to.availableAmount, moved)
        }
    }

    private fun requireTargetAvailable(to: StockUnit) {
        if (to.lockType != LockType.UNLOCKED.code) {
            throw InventoryException.StockLocked(to.id!!, to.lockType)
        }
        if (to.state != StockState.ON_STOCK.code) {
            throw InventoryException.InvalidTarget("target stock unit ${to.id} is not ON_STOCK")
        }
    }

    /**
     * On a source with any stale reference (see [requireTransferable]), the operation always
     * moves ALL live reservation, never a sub-slice of it -- sub-slice partials were already
     * refused pre-split by [requirePartialAllowed] and stay refused; splitting further than "the
     * live remainder" has no defined semantics. [ReservationSourceState.liveAmount] summed across
     * every mover's already-read [states] is the authoritative live figure, folded per slice the
     * same way [ReservationRefMover.moveRefs]'s split performs, so this check and the actual row
     * surgery always agree -- both read from the SAME snapshot. 409, naming the live amount so the
     * caller can retry with the right number.
     */
    private fun requireLiveAmountMatch(from: StockUnit, moved: BigDecimal, states: Map<ReservationRefMover, ReservationSourceState>) {
        val live = states.values.fold(BigDecimal.ZERO) { acc, state -> acc.add(state.liveAmount) }
        if (moved.compareTo(live) != 0) {
            throw InventoryException.Encumbered(
                "stock unit ${from.id} mixes terminal and open reservation history; the requested " +
                    "amount $moved must equal the live remainder $live",
            )
        }
    }

    /**
     * Splitting an `OrderLineReservation` row is a different operation with its own semantics,
     * so a partial move (the moved amount is less than the source's whole reservedAmount) is
     * refused outright while any LIVE order-line or open-pick reference still exists on the
     * source. [InventoryException.Encumbered] (409), not `ValidationFailed` (400, reserved for
     * bad caller input) -- the source is encumbered by a live claim, the same shape as the
     * open-pick refusal `changeClient` already models. [ReservationRefMover.countRefs] only counts
     * non-stale, live references; a source with any stale reference takes [requireLiveAmountMatch]
     * instead (see [requireTransferable]). Kept as its own lightweight query (not folded into
     * [ReservationSourceState]) since it only runs on the clean, no-stale-history branch.
     */
    private fun requirePartialAllowed(from: StockUnit, moved: BigDecimal) {
        if (moved.compareTo(from.reservedAmount) != 0 &&
            refMovers.sumOf { it.countRefs(from.id!!, from.clientId) } > 0
        ) {
            throw InventoryException.Encumbered(
                "a partial reservation transfer is not available while order-line or pick references exist " +
                    "on stock unit ${from.id}",
            )
        }
    }

    private fun journalReservationTransfer(from: StockUnit, to: StockUnit, moved: BigDecimal, tenant: TenantContext) {
        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = from,
            tenant = tenant,
            amount = moved.negate(),
            fromUnitLoad = from.unitLoad.labelId,
            fromLocation = from.unitLoad.storageLocationName,
            toUnitLoad = to.unitLoad.labelId,
            toLocation = to.unitLoad.storageLocationName,
            activityCode = ACTIVITY_CODE,
        )
        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = to,
            tenant = tenant,
            amount = moved,
            fromUnitLoad = from.unitLoad.labelId,
            fromLocation = from.unitLoad.storageLocationName,
            toUnitLoad = to.unitLoad.labelId,
            toLocation = to.unitLoad.storageLocationName,
            activityCode = ACTIVITY_CODE,
        )
    }

    private companion object {
        const val ACTIVITY_CODE = "TRANSFER_RESERVATION"
    }
}

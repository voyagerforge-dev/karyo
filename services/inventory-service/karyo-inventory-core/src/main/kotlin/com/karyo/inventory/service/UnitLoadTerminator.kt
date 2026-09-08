package com.karyo.inventory.service

import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.event.UnitLoadTrashedEvent
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional

/**
 * Single choke point for a unit load going terminal because its stock physically left it,
 * defect-burndown-4 task-10, row 14. Before this class the journal/outbox/CDI trio that marks a
 * unit load DELETABLE and releases its `StorageLocation.allocation` was inlined twice
 * ([DefaultStockCountingPort.applyCount]'s zero-count branch and [UnitLoadService.delete]'s hard
 * delete) and simply MISSING from every other producer that can drain a unit load to empty on an
 * occupied location: [StockService.transferStock]'s merge-drain, [StockService.deleteStock] (via
 * its callers), and [DefaultStockPicker.shipContainer]. Both inline trios now collapse onto
 * [trashIfEmpty] / [fireTrashed] respectively, and every listed producer calls [trashIfEmpty]
 * directly or via its caller.
 *
 * **Gone-predicate:** a stock unit no longer keeps its unit load alive once
 * `state in (`[StockState.SHIPPED]`, `[StockState.DELETABLE]`)`, since shipped stock has left the
 * building and DELETABLE stock is the soft-delete tombstone itself. [StockState.PACKED] stock is
 * deliberately NOT gone: it is still physically sitting in the container, which is why
 * [DefaultStockPicker.packContainer] gets no call into this class at all.
 *
 * **CDI direction:** this bean is injected INTO [StockService], the stock ports, and
 * [UnitLoadService], never the other way. [UnitLoadService] already injects [StockService], so a
 * dependency back from here to either would form a cycle.
 *
 * **Ownership check is integrity, not authorization:** every caller has already resolved
 * [clientId] from an entity whose write-scope was checked earlier in the same call (e.g.
 * `StockUnit.clientId` off a row loaded via `findByIdForWrite`). [trashIfEmpty] simply requires
 * the freshly-reloaded unit load to still carry that SAME owner before flipping it, the same
 * "second entity must be scoped too" idiom used throughout this module (see
 * `StockService.unitLoadForWrite`'s KDoc), rather than re-deriving a fresh
 * [com.karyo.security.TenantScope] permission from [tenant]. [AdjustDeletableGuardTest]'s
 * cross-owner rig (a stock unit whose `clientId` diverges from its parent unit load's, built only
 * by bypassing every write path) is exactly the case this equality check refuses. [tenant] itself
 * is only needed downstream, for [JournalService.recordUnitLoadTrashed]'s `operatorName`.
 */
@ApplicationScoped
class UnitLoadTerminator(
    private val unitLoadRepository: UnitLoadRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val journalService: JournalService,
    private val outboxService: OutboxService,
    private val trashedEvent: Event<UnitLoadTrashedEvent>,
) {

    /**
     * Re-reads every stock unit under [unitLoadId], deliberately UNSCOPED, mirroring
     * [DefaultStockCountingPort.applyCount]'s original rationale: physical emptiness of a pallet
     * is owner-independent, so the check must see every stock unit riding it, not just the
     * caller's own. If any surviving unit is NOT gone (see class KDoc), this is a no-op.
     *
     * Otherwise reloads the unit load and, only when [clientId] still matches its owner AND it is
     * not already DELETABLE (idempotency: a caller can land fresh stock on an already-trashed UL,
     * per the WORKLIST gap this task also closes at the transfer choke points), flips it terminal
     * and fires the trio exactly once via [fireTrashed].
     *
     * **manageEmpties suppressor (row 16, signal changed by A1 -- defect-burndown-5):** a unit
     * load whose type has [com.karyo.inventory.domain.model.UnitLoadType.manageEmpties] set is a
     * reusable container (tote/pallet) that stays in circulation once emptied rather than being
     * trashed. This only applies to the empty-check arm: a unit load gone because it shipped (it
     * physically left the building) is still gone regardless of the type flag. Originally that
     * distinction was read off stock state (any row still SHIPPED meant "gone because shipped");
     * A1 made [DefaultStockPicker.shipContainer] promote every shipped stock unit straight to
     * DELETABLE in the same transaction, BEFORE this method runs, so by the time [trashIfEmpty]
     * inspects [allStock] a shipped unit load reads no differently from an otherwise-trashed one
     * -- both are all-DELETABLE. The discriminator is therefore [activityCode] instead: callers
     * that reach here because of a ship pass [DefaultStockPicker.ACTIVITY_SHIP] directly (review
     * fix: a shared `internal` constant, not a second string literal kept in sync by comment --
     * see that constant's own KDoc), and only that caller does, so the suppressor now keys off
     * "was this call triggered by a ship" rather than "is any row still SHIPPED".
     */
    @Transactional
    fun trashIfEmpty(unitLoadId: Long, clientId: Long, tenant: TenantContext, activityCode: String?) {
        val allStock = stockUnitRepository.findByUnitLoadId(unitLoadId)
        if (allStock.any { it.state !in GONE_STATES }) return

        val ul = unitLoadRepository.findById(unitLoadId)
            ?.takeIf { it.clientId == clientId && it.state != StockState.DELETABLE.code }
            ?: return

        // Row 16 / A1: a manageEmpties type keeps its empty unit loads. They are reusable
        // containers going back into circulation, not containers leaving the system. This
        // guards the empty arm only: a unit load that is gone because it shipped is still gone
        // (see this method's KDoc for why activityCode, not stock state, is the signal now).
        if (activityCode != DefaultStockPicker.ACTIVITY_SHIP && ul.unitLoadType.manageEmpties) return

        ul.state = StockState.DELETABLE.code
        fireTrashed(ul, allStock.map { it.id!! }, activityCode, tenant)
    }

    /**
     * Fires the journal + outbox + CDI trio for a unit load that has already gone terminal.
     * Shared by [trashIfEmpty]'s soft state-flip path and [UnitLoadService.delete]'s hard
     * row-removal path; this function does not itself decide emptiness or mutate [ul]/the
     * database, callers own that.
     */
    @Transactional
    fun fireTrashed(ul: UnitLoad, stockUnitIds: List<Long>, activityCode: String?, tenant: TenantContext) {
        val event = UnitLoadTrashedEvent(
            unitLoadId = ul.id!!,
            labelId = ul.labelId,
            locationId = ul.storageLocationId,
            stockUnitIds = stockUnitIds,
        )
        journalService.recordUnitLoadTrashed(
            ul.clientId,
            ul.labelId,
            ul.storageLocationName,
            tenant,
            activityCode,
        )
        outboxService.publish(
            aggregateType = "UnitLoad",
            aggregateId = ul.id!!,
            eventType = "UnitLoadTrashed",
            payload = event,
            tenantId = ul.clientId,
        )
        trashedEvent.fire(event)
    }

    /**
     * `internal` (not `private`): [UnitLoadWeightCalculator] shares this exact gone-predicate
     * (a unit load's weight excludes stock that has left the building or is the soft-delete
     * tombstone -- the same two states that stop a stock unit from keeping a unit load alive,
     * see this class's KDoc) and reuses this set rather than re-deriving its own, so the two
     * cannot silently desync. Unaffected by A1 (defect-burndown-5)'s ship-time DELETABLE
     * promotion: SHIPPED was already gone-predicate territory before that change, DELETABLE
     * still is after it, membership in this set does not change either way.
     */
    internal companion object {
        val GONE_STATES = setOf(StockState.SHIPPED.code, StockState.DELETABLE.code)
    }
}

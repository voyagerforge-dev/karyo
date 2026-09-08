package com.karyo.inventory.service

import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.api.event.UnitLoadRevivedEvent
import com.karyo.inventory.api.spi.StockPicker
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

/**
 * Default [StockPicker]: composes the existing stock primitives — releases the source's reservation
 * for the picked quantity ([StockService.releaseReservation]), then [StockService.transferStock]
 * moves the amount onto the pick container (inheriting the source ON_STOCK state, decrementing
 * the source, journaling TRANSFERRED), then [StockService.changeState] flips the picked stock to
 * PICKED(600) (journaling CHANGED). Activity code "PICK".
 *
 * **Accumulation-safe:** when the pick container already holds matching SKU+lot stock and its UL
 * type aggregates, transferStock *merges* into that (already-PICKED) unit, so the changeState is
 * skipped (a forward-only re-flip to PICKED would otherwise throw). pickStock is thus idempotent
 * on state for a container accumulating multiple picks of the same item.
 */
@ApplicationScoped
class DefaultStockPicker(
    private val stockService: StockService,
    private val unitLoadService: UnitLoadService,
    private val tenantContext: TenantContext,
    private val unitLoadTerminator: UnitLoadTerminator,
    private val journalService: JournalService,
    private val weightCalculator: UnitLoadWeightCalculator,
    private val unitLoadRepository: UnitLoadRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val outboxService: OutboxService,
    private val revivedEvent: Event<UnitLoadRevivedEvent>,
) : StockPicker {

    @Transactional
    override fun pickStock(sourceStockUnitId: Long, amount: BigDecimal, targetUnitLoadId: Long): Long {
        // A pick consumes the source's reservation for the picked quantity. Release the reserved
        // portion FIRST so the transfer — which validates availableAmount (= amount - reservedAmount)
        // — can draw goods reserved for this order. (v1.3 pick-to-container model: a pick decrements
        // both amount and reservedAmount on the source.) Release only what is actually reserved so an
        // unreserved pick produces no spurious reservation-release journal row.
        val reserved = stockService.findByIdForWrite(sourceStockUnitId, tenantContext).reservedAmount
        val toRelease = amount.coerceAtMost(reserved)
        if (toRelease > BigDecimal.ZERO) {
            stockService.releaseReservation(sourceStockUnitId, toRelease, ACTIVITY_PICK, tenantContext)
        }
        val picked = stockService.transferStock(sourceStockUnitId, targetUnitLoadId, amount, ACTIVITY_PICK, tenantContext)
        // Only flip a freshly-created (ON_STOCK) target; a merged unit is already PICKED and a
        // re-flip would violate forward-only. TODO(v1.3): thread correlationId/activityCode through
        // changeState so the PICKED journal row carries pick context (transferStock already does).
        if (picked.state != StockState.PICKED.code) {
            stockService.changeState(picked.id!!, StockState.PICKED.code, tenantContext)
        }
        return picked.id!!
    }

    /**
     * **Task 9 fix (wave bulk fulfillment sprint):** the ambient-injected [tenantContext] this
     * class uses everywhere else is deliberately NOT used here. [clientId] already arrives as an
     * explicit parameter (this method's caller resolves it correctly end to end -- including
     * from `WaveScheduler`'s `@Scheduled` multi-tenant auto-release loop, whose thread never
     * primes `TenantContext`), but [unitLoadService].`create`'s owner-authorization check
     * ([UnitLoadService.resolveOwner]) re-derives permission from the `TenantContext` it is
     * handed, not from the request's own `clientId` field alone -- passing the unprimed ambient
     * bean would fail `writeScope().permits(clientId)` for every real tenant on a scheduler tick
     * (unprimed default is `OWNER(0)`, which only permits client 0). [TenantContext.ownerScoped]
     * is the shared factory for that: a synthetic, strictly-scoped `TenantContext` built from
     * the explicit `clientId`, never derived from the ambient field --
     * and [requireOwnedBy] pairs with it exactly as `DefaultStockReserver.requireOwnedBy` pairs
     * with its own `ownerScoped` calls (reviewer fold-2): the created row's actual owner is
     * asserted against the caller's explicit `clientId` before this method hands the id back, so
     * a future `resolveOwner`/`ownerScoped` drift fails loudly instead of silently attributing a
     * pick container to the wrong tenant.
     */
    @Transactional
    override fun createPickContainer(
        clientId: Long,
        unitLoadTypeId: Long,
        locationId: Long,
        locationName: String,
        labelId: String,
    ): Long {
        val ul = unitLoadService.create(
            CreateUnitLoadRequest(
                clientId = clientId,
                labelId = labelId,
                unitLoadTypeId = unitLoadTypeId,
                storageLocationId = locationId,
                storageLocationName = locationName,
            ),
            TenantContext.ownerScoped(clientId, tenantContext.username),
        )
        requireOwnedBy(ul.clientId, clientId, ul.id!!)
        return ul.id!!
    }

    /** Same shape as `DefaultStockReserver.requireOwnedBy`: a created unit load whose actual
     *  owner drifts from the caller's explicit [expectedClientId] throws [InventoryException
     *  .Forbidden] rather than being silently handed back attributed to the wrong tenant. */
    private fun requireOwnedBy(actualClientId: Long, expectedClientId: Long, unitLoadId: Long) {
        if (actualClientId != expectedClientId) {
            throw InventoryException.Forbidden(
                "UnitLoad $unitLoadId belongs to client $actualClientId, not $expectedClientId",
            )
        }
    }

    @Transactional
    override fun releaseUnpickedReservation(sourceStockUnitId: Long, amount: BigDecimal) =
        doReleaseUnpicked(sourceStockUnitId, amount, tenantContext)

    @Transactional
    override fun releaseUnpickedReservation(sourceStockUnitId: Long, amount: BigDecimal, clientId: Long) =
        doReleaseUnpicked(sourceStockUnitId, amount, TenantContext.ownerScoped(clientId, tenantContext.username))

    /**
     * Shared body behind both [releaseUnpickedReservation] overloads -- [tenant] is either the
     * injected ambient [tenantContext] or a [TenantContext.ownerScoped] one built from an explicit
     * `clientId`; the body itself never reads ambient state directly (same shape as
     * `DefaultStockReserver.doReserve`).
     */
    private fun doReleaseUnpicked(sourceStockUnitId: Long, amount: BigDecimal, tenant: TenantContext) {
        if (amount.signum() <= 0) return
        val reserved = stockService.findByIdForWrite(sourceStockUnitId, tenant).reservedAmount
        val toRelease = amount.coerceAtMost(reserved)
        if (toRelease > BigDecimal.ZERO) {
            stockService.releaseReservation(sourceStockUnitId, toRelease, ACTIVITY_PICK, tenant)
        }
    }

    @Transactional
    override fun packContainer(unitLoadId: Long): Int = doPack(unitLoadId, tenantContext)

    @Transactional
    override fun packContainer(unitLoadId: Long, clientId: Long): Int =
        doPack(unitLoadId, TenantContext.ownerScoped(clientId, tenantContext.username))

    /**
     * Shared body behind both [packContainer] overloads -- same [tenant]-parameterization as
     * [doReleaseUnpicked].
     *
     * Row 16 (fix round 1): one recompute for the whole load after the loop, not one per item.
     */
    private fun doPack(unitLoadId: Long, tenant: TenantContext): Int {
        val picked = stockService.findByUnitLoad(unitLoadId, tenant)
            .filter { it.state == StockState.PICKED.code }
        picked.forEach { stockService.changeState(it.id!!, StockState.PACKED.code, tenant, recalculateWeight = false) }
        if (picked.isNotEmpty()) weightCalculator.recalculate(picked.first().unitLoad)
        return picked.size
    }

    /**
     * Flips every PACKED unit on [unitLoadId] to SHIPPED, then -- adjudication A1
     * (defect-burndown-5, rows :1537/:1595) -- promotes each of those same stock units straight
     * on to DELETABLE(1000), in the same transaction, before [UnitLoadTerminator.trashIfEmpty]
     * runs. SHIPPED remains the recorded ship state in the journal/outbox (journaled via the
     * plain, unmarked `changeState` call exactly as before this sprint); the DB end state after
     * `shipContainer` returns is DELETABLE, journaled again via [ACTIVITY_SHIP_CLEANUP]. This
     * closes the SHIPPED-ghost-occupant exposure documented on [OccupancyMixReader]
     * (`occupantsByLocationIds`/`itemStocksByLocationIds` both filter `state < DELETABLE`, so a
     * promoted row drops out of both immediately) regardless of [StockPurgeService]'s reaper
     * posture (opt-in, default off), and makes the reaper's own retention window --
     * "shipped stock purges N days after ship" -- literally true, since the promotion is what
     * stamps `modified` at ship time.
     *
     * Shipped stock has physically left the building, so once nothing else keeps the container
     * occupied this is a producer of an empty unit load too: [UnitLoadTerminator.trashIfEmpty]
     * fires the trio when that happens (task-10, defect-burndown-4, row 14). `packContainer` is
     * deliberately NOT wired the same way: PACKED stock is still physically sitting in the
     * container, so packing never empties anything.
     *
     * **Ordering note (A1's corollary):** promoting every stock unit to DELETABLE before
     * `trashIfEmpty` runs means `trashIfEmpty` can no longer tell "gone because shipped" apart
     * from "gone because otherwise trashed" by stock state alone -- both read DELETABLE by the
     * time it inspects them. [UnitLoadTerminator.trashIfEmpty]'s manageEmpties suppressor was
     * updated (this same sprint) to use the `activityCode` it is already called with
     * ([ACTIVITY_SHIP] here) as that signal instead; see its KDoc.
     */
    @Transactional
    override fun shipContainer(unitLoadId: Long): Int {
        val packed = stockService.findByUnitLoad(unitLoadId, tenantContext)
            .filter { it.state == StockState.PACKED.code }
        packed.forEach { stockService.changeState(it.id!!, StockState.SHIPPED.code, tenantContext, recalculateWeight = false) }
        if (packed.isNotEmpty()) {
            // Row 16 (fix round 1): one recompute for the whole load, not one per flipped item.
            weightCalculator.recalculate(packed.first().unitLoad)
            // A1: promote SHIPPED -> DELETABLE for every just-shipped stock unit, same
            // transaction, before trashIfEmpty (see this method's KDoc for why the ordering
            // matters to trashIfEmpty's manageEmpties suppressor).
            packed.forEach {
                stockService.changeState(
                    it.id!!,
                    StockState.DELETABLE.code,
                    tenantContext,
                    recalculateWeight = false,
                    activityCode = ACTIVITY_SHIP_CLEANUP,
                )
            }
            unitLoadTerminator.trashIfEmpty(unitLoadId, packed.first().clientId, tenantContext, ACTIVITY_SHIP)
        }
        return packed.size
    }

    /**
     * S4: DELIBERATE forward-only exception (see the [StockPicker.unpackContainer] KDoc). Writes
     * the target state directly onto the [com.karyo.inventory.domain.model.StockUnit] entity
     * rather than going through [StockService.changeState] -- that method enforces forward-only
     * progression and has no path from PACKED back to PICKED/ON_STOCK. `StockService` itself is
     * untouched by this method. Journals one CHANGED row per flipped unit (activityCode "UNPACK")
     * via [JournalService.record], which attributes `clientId` from the stock unit itself (the
     * entity-owner), never from the tenant scope -- identical to every other journal-writing path
     * in this class, and unchanged by the explicit-`clientId` overload below.
     */
    @Transactional
    override fun unpackContainer(unitLoadId: Long, restoreToOnStock: Boolean): Int =
        doUnpack(unitLoadId, restoreToOnStock, tenantContext)

    @Transactional
    override fun unpackContainer(unitLoadId: Long, restoreToOnStock: Boolean, clientId: Long): Int =
        doUnpack(unitLoadId, restoreToOnStock, TenantContext.ownerScoped(clientId, tenantContext.username))

    /**
     * Shared body behind both [unpackContainer] overloads -- same [tenant]-parameterization as
     * [doReleaseUnpicked]. [JournalService.record] still attributes `clientId` from the stock unit
     * itself (the entity-owner), never from [tenant]; [tenant] only decides which rows are in scope.
     */
    private fun doUnpack(unitLoadId: Long, restoreToOnStock: Boolean, tenant: TenantContext): Int {
        val target = if (restoreToOnStock) StockState.ON_STOCK else StockState.PICKED
        val packed = stockService.findByUnitLoad(unitLoadId, tenant)
            .filter { it.state == StockState.PACKED.code }
        packed.forEach { su ->
            su.state = target.code
            journalService.record(
                recordType = JournalRecordType.CHANGED,
                stockUnit = su,
                tenant = tenant,
                toUnitLoad = su.unitLoad.labelId,
                toLocation = su.unitLoad.storageLocationName,
                activityCode = ACTIVITY_UNPACK,
            )
        }
        return packed.size
    }

    /**
     * S5: mechanical mirror of [packContainer], but the source state is ON_STOCK(300) rather
     * than PICKED(600) -- see [StockPicker.packAdHocContainer]'s KDoc for why this is a separate
     * method rather than a flag on [packContainer]. Journals via the same
     * [StockService.changeState] path (activityCode default), so this reuses the ordinary
     * forward-only ON_STOCK -> PACKED transition -- no need for [unpackContainer]'s
     * direct-field-write exception, this direction is already legal.
     */
    /** Row 16 (fix round 1): one recompute for the whole load after the loop, not one per item. */
    @Transactional
    override fun packAdHocContainer(unitLoadId: Long): Int {
        val onStock = stockService.findByUnitLoad(unitLoadId, tenantContext)
            .filter { it.state == StockState.ON_STOCK.code }
        onStock.forEach { stockService.changeState(it.id!!, StockState.PACKED.code, tenantContext, recalculateWeight = false) }
        if (onStock.isNotEmpty()) weightCalculator.recalculate(onStock.first().unitLoad)
        return onStock.size
    }

    /**
     * Sprint C: see [StockPicker.reviveDrainedContainer]. Reads the repositories directly rather
     * than [StockService]/[UnitLoadService] because both of those resolve scope from the ambient
     * [TenantContext], and this method is contracted on an EXPLICIT [clientId] -- the strict
     * `clientId` equality check below IS the scope, the `DefaultUnitLoadLookup.findById(id,
     * clientId)` precedent. Never throws: an unknown, foreign or already-live unit load simply
     * flips nothing and returns 0.
     */
    @Transactional
    override fun reviveDrainedContainer(unitLoadId: Long, itemDataId: Long, lotNumber: String?, clientId: Long): Int {
        val ul = unitLoadRepository.findById(unitLoadId)?.takeIf { it.clientId == clientId } ?: return 0
        val revivedStock = reviveEmptiedStock(unitLoadId, itemDataId, lotNumber, clientId)
        if (ul.state != StockState.DELETABLE.code) return revivedStock.size
        ul.state = StockState.UNDEFINED.code
        ul.modified = Instant.now()
        fireRevived(ul, revivedStock.map { it.id!! })
        return revivedStock.size + 1
    }

    /**
     * The targeted half of [reviveDrainedContainer]: only the EMPTIED rows of exactly this
     * item + lot, never every zero-amount tombstone on the cart (see the SPI KDoc for why a
     * blanket flip strands permanent ghosts on a multi-SKU cart). Journaled per row with the
     * same [ACTIVITY_UNPACK] code [unpackContainer] uses -- both are the same reversal.
     *
     * **Lot comparison is normalized (IMPORTANT 4, Sprint C final-review fix wave):** null and ""
     * are the SAME lot, exactly as `ShippingLifecycleService.sourceStockFor` reads it on the other
     * half of the same restore. `ShippingUnitLine.lotNumber` reaches this method from a container
     * line whose "no lot" can be either value depending on how it was minted, and a strict `==`
     * silently revived nothing for the mismatching pair -- leaving the cart's row tombstoned and
     * the subsequent move-back to fail against a DELETABLE target.
     */
    private fun reviveEmptiedStock(unitLoadId: Long, itemDataId: Long, lotNumber: String?, clientId: Long): List<StockUnit> {
        val wantedLot = lotNumber ?: ""
        val matching = stockUnitRepository.findByUnitLoadId(unitLoadId).filter {
            it.clientId == clientId &&
                it.state == StockState.DELETABLE.code &&
                it.amount.signum() == 0 &&
                it.itemDataId == itemDataId &&
                (it.lotNumber ?: "") == wantedLot
        }
        matching.forEach { su ->
            su.state = StockState.PICKED.code
            su.modified = Instant.now()
            journalService.record(
                recordType = JournalRecordType.CHANGED,
                stockUnit = su,
                tenant = tenantContext,
                toUnitLoad = su.unitLoad.labelId,
                toLocation = su.unitLoad.storageLocationName,
                activityCode = ACTIVITY_UNPACK,
            )
        }
        return matching
    }

    /**
     * The exact inverse of [UnitLoadTerminator.fireTrashed]: journal row, outbox row and the CDI
     * event whose layout observer re-takes the `StorageLocation.allocation` the trash released.
     * Same trio, same order, opposite direction -- so a trash/revive round trip is neutral.
     */
    private fun fireRevived(ul: UnitLoad, revivedStockUnitIds: List<Long>) {
        val event = UnitLoadRevivedEvent(
            unitLoadId = ul.id!!,
            labelId = ul.labelId,
            locationId = ul.storageLocationId,
            stockUnitIds = revivedStockUnitIds,
        )
        journalService.recordUnitLoadRevived(
            ul.clientId,
            ul.labelId,
            ul.storageLocationName,
            tenantContext,
            ACTIVITY_UNPACK,
        )
        outboxService.publish(
            aggregateType = "UnitLoad",
            aggregateId = ul.id!!,
            eventType = "UnitLoadRevived",
            payload = event,
            tenantId = ul.clientId,
        )
        revivedEvent.fire(event)
    }

    /**
     * `internal` (not `private`), review fix (defect-burndown-5, Task 1 follow-up):
     * [ACTIVITY_SHIP] is referenced directly by [UnitLoadTerminator.trashIfEmpty]'s
     * manageEmpties-suppressor check (`activityCode != DefaultStockPicker.ACTIVITY_SHIP`) --
     * same "internal, not private, because another class in this module needs the exact same
     * value" rationale as [UnitLoadTerminator.GONE_STATES]. A single shared reference instead of
     * a duplicated string literal in two files: nothing to fall out of sync.
     */
    internal companion object {
        const val ACTIVITY_PICK = "PICK"
        const val ACTIVITY_SHIP = "SHIP"
        const val ACTIVITY_UNPACK = "UNPACK"

        /** The SHIPPED -> DELETABLE cleanup transition, A1 (defect-burndown-5). Distinct from
         *  [ACTIVITY_SHIP] so the journal can tell the two transitions in [shipContainer] apart. */
        const val ACTIVITY_SHIP_CLEANUP = "SHIP_CLEANUP"
    }
}

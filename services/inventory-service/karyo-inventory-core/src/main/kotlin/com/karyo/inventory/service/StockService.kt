package com.karyo.inventory.service

import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.inventory.api.dto.CreateStockUnitRequest
import com.karyo.inventory.api.dto.ReserveStockResponse
import com.karyo.inventory.api.event.LockChangedEvent
import com.karyo.inventory.api.event.PackagingUnitChangedEvent
import com.karyo.inventory.api.event.StockUnitAmountChangedEvent
import com.karyo.inventory.api.event.StockUnitDeletedEvent
import com.karyo.inventory.api.event.StockUnitStateChangedEvent
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.events.outbox.OutboxService
import com.karyo.security.TenantContext
import com.karyo.security.TenantScope
import com.karyo.security.readScope
import com.karyo.security.writeScope
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.product.spi.PackagingUnitLookup
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
class StockService(
    private val stockUnitRepository: StockUnitRepository,
    private val unitLoadRepository: UnitLoadRepository,
    private val journalService: JournalService,
    private val outboxService: OutboxService,
    private val packagingUnitLookup: PackagingUnitLookup,
    private val unitLoadTerminator: UnitLoadTerminator,
    private val stockAmountEventPublisher: StockAmountEventPublisher,
    private val weightCalculator: UnitLoadWeightCalculator,
) {

    fun findById(id: Long, tenant: TenantContext): StockUnit {
        val su = stockUnitRepository.findById(id)
            ?: throw InventoryException.NotFound("StockUnit", id)
        if (!tenant.readScope().permits(su.clientId)) {
            throw InventoryException.NotFound("StockUnit", id)
        }
        return su
    }

    /**
     * Load for mutation paths, owner-blind for an ops principal only (see the tenant isolation
     * design doc §5.1): ops staff physically handle every owner's goods, so an OPS mutation is
     * not gated on the actor's own client_id. A goods-owner principal stays scoped to its own
     * rows — it has no business mutating another owner's stock. Attribution (the row's
     * `clientId`) is never reassigned by callers regardless of who performed the write —
     * with exactly one sanctioned exception: [UnitLoadService.changeClient], an OPS-only
     * operation that refuses on any encumbrance and journals the move as paired
     * DELETED/CREATED rows under the old and new owner.
     *
     * Do NOT use this to serve a read endpoint; use [findById].
     */
    fun findByIdForWrite(id: Long, tenant: TenantContext): StockUnit {
        val su = stockUnitRepository.findById(id)
            ?: throw InventoryException.NotFound("StockUnit", id)
        if (!tenant.writeScope().permits(su.clientId)) {
            throw InventoryException.NotFound("StockUnit", id)
        }
        return su
    }

    /**
     * Refuses any amount write onto a soft-deleted (DELETABLE) stock unit — resurrecting a unit
     * the warehouse already wrote off. Precedent: [DefaultStockReceiver.requireNotAlreadyReversed]
     * (same exception, same 409 `invalid-state-transition` mapping). Reachable in production only
     * through REST `POST /stock-units/{id}/adjust` — [DefaultStockCountingPort.applyCount] can
     * never hit DELETABLE, since [DefaultStockCountingPort.findStockAtLocation] filters it out at
     * plan time.
     */
    private fun requireNotDeleted(su: StockUnit) {
        if (su.state == StockState.DELETABLE.code) {
            throw InventoryException.InvalidStateTransition(su.id!!, su.state, su.state)
        }
    }

    /**
     * Loads a unit load that a stock mutation will *trust* — as the parent whose owner the new
     * stock inherits, or as a transfer target the stock lands on.
     *
     * Scoping the subject of a mutation is not enough: any second entity whose value the
     * mutation reads must be scoped too, or the owner check is sidestepped by naming someone
     * else's unit load. Throws `NotFound` rather than `Forbidden`, matching the read paths,
     * so existence does not leak.
     *
     * DELETABLE-target guard (task-10, defect-burndown-4, row 14, split out into
     * [requireUnitLoadNotDeleted] to keep this under detekt's `ThrowsCount` limit): a unit load a
     * [UnitLoadTerminator] flip already marked terminal must refuse new stock, whether that is
     * [createStock] attributing a fresh unit to it or [transferStock] landing more amount on it:
     * the pallet is gone, and accepting more would resurrect it without ever flipping the
     * state back. Same idiom as [requireNotDeleted], one level up.
     */
    private fun unitLoadForWrite(unitLoadId: Long, tenant: TenantContext): UnitLoad {
        val ul = unitLoadRepository.findById(unitLoadId)
            ?: throw InventoryException.NotFound("UnitLoad", unitLoadId)
        if (!tenant.writeScope().permits(ul.clientId)) {
            throw InventoryException.NotFound("UnitLoad", unitLoadId)
        }
        requireUnitLoadNotDeleted(ul)
        return ul
    }

    /**
     * Second [unitLoadForWrite] guard, split out so that function stays under detekt's
     * `ThrowsCount` limit (same reason [validatePackagingUnit] is split from [createStock]).
     */
    private fun requireUnitLoadNotDeleted(ul: UnitLoad) {
        if (ul.state == StockState.DELETABLE.code) {
            throw InventoryException.InvalidStateTransition(ul.id!!, ul.state, ul.state)
        }
    }

    /**
     * D1 (user decision 2026-07-25): cross-owner movement is REFUSED — changeClient is the
     * single sanctioned ownership-transfer path (total, guarded, journaled under both owners).
     * This is an INTEGRITY comparison, not authorization (tenant-isolation design §5.1):
     * physical segregation of ownership is the same invariant validateReusable enforces at
     * receive time; OPS being write-unscoped does not exempt it. Split out from [transferStock]
     * to keep it under detekt's `LongMethod` limit, matching the [validatePackagingUnit]
     * precedent for the same shape of extraction.
     */
    private fun requireSameOwnerForTransfer(source: StockUnit, targetUL: UnitLoad) {
        if (source.clientId != targetUL.clientId) {
            throw InventoryException.CrossOwner(
                "cannot transfer stock owned by client ${source.clientId} onto a unit load owned by " +
                    "client ${targetUL.clientId} — use change-client to reassign ownership first",
            )
        }
    }

    /**
     * Resolves what [transferStock] lands the moved amount on: merges into a same-owner,
     * same-SKU/lot stock unit already on the target UL when its type aggregates stocks,
     * otherwise creates a new stock unit there. Split out from [transferStock] to keep it
     * under detekt's `LongMethod` limit, matching the [validatePackagingUnit] precedent.
     */
    private fun resolveTransferTargetStock(
        source: StockUnit,
        targetUL: UnitLoad,
        amount: BigDecimal,
        activityCode: String,
    ): StockUnit {
        val existingMatch = stockUnitRepository.findByUnitLoadId(targetUL.id!!)
            .firstOrNull {
                it.itemDataId == source.itemDataId &&
                    it.lotNumber == source.lotNumber &&
                    it.clientId == source.clientId
            }
        if (existingMatch != null && targetUL.unitLoadType.aggregateStocks) {
            existingMatch.amount = existingMatch.amount.add(amount)
            return existingMatch
        }
        val newSU = StockUnit().apply {
            clientId = source.clientId
            itemDataId = source.itemDataId
            itemDataNumber = source.itemDataNumber
            this.amount = amount
            unitLoad = targetUL
            lotNumber = source.lotNumber
            serialNumber = source.serialNumber
            bestBefore = source.bestBefore
            state = source.state
            this.activityCode = activityCode
            strategyDate = source.strategyDate
            packagingUnitId = source.packagingUnitId
        }
        stockUnitRepository.persist(newSU)
        return newSU
    }

    /**
     * A5: `packagingUnitId` is validated at stock creation instead of stored blind --
     * it must both exist and belong to the item being received. Split out from
     * [createStock] so its own throw count stays under detekt's `ThrowsCount` limit,
     * matching the [unitLoadForWrite] precedent for the same rule shape.
     */
    private fun validatePackagingUnit(request: CreateStockUnitRequest) {
        val puId = request.packagingUnitId ?: return
        val pu = packagingUnitLookup.findById(puId)
            ?: throw InventoryException.ValidationFailed("unknown packaging unit $puId")
        if (pu.itemDataId != request.itemDataId) {
            throw InventoryException.ValidationFailed(
                "packaging unit $puId does not belong to item ${request.itemDataId}"
            )
        }
    }

    fun findByItemData(itemDataId: Long, tenant: TenantContext): List<StockUnit> =
        stockUnitRepository.findByItemDataId(itemDataId, tenant.clientId)

    fun findByUnitLoad(unitLoadId: Long, tenant: TenantContext): List<StockUnit> {
        val scope = tenant.readScope()
        return stockUnitRepository.findByUnitLoadId(unitLoadId)
            .filter { scope.permits(it.clientId) }
    }

    /** Returns a page of all stock units for the tenant. */
    fun findAllPaginated(tenant: TenantContext, pagination: PaginationParams): PaginatedEntities<StockUnit> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.descending("created"))
        val query = stockUnitRepository.find("clientId", sort, tenant.clientId)
            .page(Page.of(pagination.page, pagination.size))
        return PaginatedEntities(query.list(), query.count())
    }

    /** Returns a page of stock units filtered by itemDataId. */
    fun findByItemDataPaginated(itemDataId: Long, tenant: TenantContext, pagination: PaginationParams): PaginatedEntities<StockUnit> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.descending("created"))
        val query = stockUnitRepository.find("itemDataId = ?1 and clientId = ?2", sort, itemDataId, tenant.clientId)
            .page(Page.of(pagination.page, pagination.size))
        return PaginatedEntities(query.list(), query.count())
    }

    /** Returns a page of stock units filtered by unitLoadId, scoped to tenant. */
    fun findByUnitLoadPaginated(unitLoadId: Long, tenant: TenantContext, pagination: PaginationParams): PaginatedEntities<StockUnit> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.descending("created"))
        val query = stockUnitRepository.find("unitLoad.id = ?1 and clientId = ?2", sort, unitLoadId, tenant.clientId)
            .page(Page.of(pagination.page, pagination.size))
        return PaginatedEntities(query.list(), query.count())
    }

    /**
     * Σ on-hand amount of one item at one location.
     *
     * Counts only [StockState.ON_STOCK] — INCOMING stock is not yet on hand, and PICKED/PACKED/
     * SHIPPED has left. Sums `amount`, not `availableAmount`: this is what is physically present,
     * so reserved stock still counts.
     *
     * Tenant scoping is applied inside the query, not by filtering rows afterwards, because a
     * filtered aggregate cannot be corrected after the database has summed it.
     */
    fun readAmount(itemDataId: Long, locationId: Long, tenant: TenantContext): BigDecimal {
        val clientId = when (val scope = tenant.readScope()) {
            TenantScope.Unscoped -> null
            is TenantScope.Owner -> scope.clientId
        }
        return stockUnitRepository.sumAmountByItemAndLocation(itemDataId, locationId, clientId)
    }

    @Transactional
    fun createStock(request: CreateStockUnitRequest, tenant: TenantContext): StockUnit {
        if (request.amount < BigDecimal.ZERO) {
            throw InventoryException.ValidationFailed("amount must be non-negative")
        }
        validatePackagingUnit(request)

        // The new stock unit inherits this unit load's owner, so the parent must be within the
        // caller's write scope -- otherwise a goods-owner principal could attribute stock to
        // another customer by pointing at their pallet.
        val unitLoad = unitLoadForWrite(request.unitLoadId, tenant)

        val su = StockUnit().apply {
            // Inherited from the parent unit load, not from the acting principal: ops staff
            // create stock for owners other than themselves.
            clientId = unitLoad.clientId
            itemDataId = request.itemDataId
            itemDataNumber = request.itemDataNumber
            amount = request.amount
            this.unitLoad = unitLoad
            lotNumber = request.lotNumber
            serialNumber = request.serialNumber
            packagingUnitId = request.packagingUnitId
            bestBefore = request.bestBefore
            state = request.state
            activityCode = request.activityCode
            // FIFO: strategyDate from bestBefore or now
            strategyDate = request.bestBefore?.let {
                Instant.parse("${it}T00:00:00Z")
            } ?: Instant.now()
        }
        stockUnitRepository.persist(su)
        weightCalculator.recalculate(unitLoad) // Row 16: fresh stock changes what is on the load.

        journalService.record(
            recordType = JournalRecordType.CREATED,
            stockUnit = su,
            tenant = tenant,
            amount = su.amount,
            toUnitLoad = unitLoad.labelId,
            toLocation = unitLoad.storageLocationName,
            activityCode = request.activityCode,
        )

        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "StateChanged",
            payload = StockUnitStateChangedEvent(
                stockUnitId = su.id!!,
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                unitLoadId = unitLoad.id!!,
                oldState = StockState.UNDEFINED.code,
                newState = su.state,
                amount = su.amount,
                locationId = unitLoad.storageLocationId,
                locationName = unitLoad.storageLocationName,
            ),
            // Whose goods the event is ABOUT, not who wrote it. The webhook relay compares a
            // subscription's owner clientId against this field, so publishing under the acting
            // principal drops every notification for stock ops staff (client 0 / SYS) touch.
            tenantId = su.clientId,
        )

        return su
    }

    @Transactional
    fun adjustAmount(
        id: Long,
        newAmount: BigDecimal,
        activityCode: String,
        tenant: TenantContext,
    ): StockUnit {
        if (newAmount < BigDecimal.ZERO) {
            throw InventoryException.ValidationFailed("newAmount must be non-negative")
        }

        val su = findByIdForWrite(id, tenant)
        requireNotDeleted(su)
        if (newAmount < su.reservedAmount) {
            throw InventoryException.ValidationFailed(
                "newAmount ($newAmount) cannot be less than reservedAmount (${su.reservedAmount})"
            )
        }

        val oldAmount = su.amount
        val changeAmount = newAmount.subtract(su.amount)
        su.amount = newAmount
        weightCalculator.recalculate(su.unitLoad) // Row 16: the amount just changed.

        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = su,
            tenant = tenant,
            amount = changeAmount,
            toUnitLoad = su.unitLoad.labelId,
            toLocation = su.unitLoad.storageLocationName,
            activityCode = activityCode,
        )

        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "AmountChanged",
            payload = StockUnitAmountChangedEvent(
                stockUnitId = su.id!!,
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                oldAmount = oldAmount,
                newAmount = newAmount,
                changeAmount = changeAmount,
                recordType = JournalRecordType.CHANGED.code,
                activityCode = activityCode,
                locationId = su.unitLoad.storageLocationId,
                locationName = su.unitLoad.storageLocationName,
            ),
            tenantId = su.clientId,
        )

        return su
    }

    /**
     * [recalculateWeight] defaults true for the ordinary single-item callers. The four bulk
     * loop sites -- [DefaultStockPicker.packContainer], [DefaultStockPicker.shipContainer],
     * [DefaultStockPicker.packAdHocContainer], [DefaultStockReceiver.markOnStock] -- pass false
     * and do their own single [UnitLoadWeightCalculator.recalculate] once after their loop, so a
     * unit load with N stock units on it gets one full recompute per bulk operation rather than
     * N (row 16, fix round 1: each loop iteration was re-running the batched
     * `findByUnitLoadId`/`findMeasuresByIds` pair for the whole load). Both paths converge on
     * the same number; this is a throughput fix, not a correctness one.
     *
     * [activityCode] defaults null, matching every existing caller's behavior unchanged. Row
     * :1537/:1595 (defect-burndown-5) added it so a caller can journal a distinct transition
     * within a multi-hop state change -- [DefaultStockPicker.shipContainer]'s SHIPPED ->
     * DELETABLE cleanup flip is the first user, so the journal can tell that transition apart
     * from the PACKED -> SHIPPED flip immediately before it.
     */
    @Transactional
    fun changeState(
        id: Long,
        newState: Int,
        tenant: TenantContext,
        recalculateWeight: Boolean = true,
        activityCode: String? = null,
    ): StockUnit {
        val su = findByIdForWrite(id, tenant)

        // Enforce forward-only state progression
        if (newState <= su.state && newState != StockState.DELETABLE.code) {
            throw InventoryException.InvalidStateTransition(id, su.state, newState)
        }
        // Validate it's a known state
        StockState.fromCode(newState)

        val oldState = su.state
        su.state = newState
        // Review fix (defect-burndown-5, Task 1 follow-up): BaseEntity.modified is a plain
        // field -- no @PreUpdate/@UpdateTimestamp/listener stamps it, and the DB's DEFAULT NOW()
        // only fires on INSERT -- so a state change must stamp it explicitly or it keeps
        // whatever value it had at creation. This is what makes StockPurgeService's retention
        // window ("N days since modified") mean anything for a state transition at all, ship-time
        // promotion included: the KDoc on shipContainer/StockPurgeService promises the window
        // counts from ship time, and this line is what keeps that promise true.
        su.modified = Instant.now()
        // Row 16: a state change can move stock in or out of the gone-predicate window.
        if (recalculateWeight) {
            weightCalculator.recalculate(su.unitLoad)
        }

        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = su,
            tenant = tenant,
            toUnitLoad = su.unitLoad.labelId,
            toLocation = su.unitLoad.storageLocationName,
            activityCode = activityCode,
        )

        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "StateChanged",
            payload = StockUnitStateChangedEvent(
                stockUnitId = su.id!!,
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                unitLoadId = su.unitLoad.id!!,
                oldState = oldState,
                newState = newState,
                amount = su.amount,
                locationId = su.unitLoad.storageLocationId,
                locationName = su.unitLoad.storageLocationName,
            ),
            tenantId = su.clientId,
        )

        return su
    }

    @Transactional
    fun setLock(id: Long, lockType: Int, tenant: TenantContext, reason: String? = null): StockUnit {
        LockType.fromCode(lockType) // validate known lock type
        val su = findByIdForWrite(id, tenant)
        val oldLock = su.lockType
        su.lockType = lockType

        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = su,
            tenant = tenant,
            toUnitLoad = su.unitLoad.labelId,
            toLocation = su.unitLoad.storageLocationName,
            activityCode = reason,
        )

        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "LockChanged",
            payload = LockChangedEvent(
                entityType = "StockUnit",
                entityId = su.id!!,
                oldLock = oldLock,
                newLock = lockType,
                locationId = su.unitLoad.storageLocationId,
            ),
            tenantId = su.clientId,
        )

        return su
    }

    /**
     * Row 15: reclassifies a stock unit's packaging unit in place. The amount is never touched,
     * as specified in `docs/functional/inventory-operations.md#packaging-unit-reclassification`.
     *
     * Refused at or past PICKED(600): reclassifying stock that has already left the shelf edits a
     * record of something that already happened. This deliberately exceeds legacy, matching the
     * tightening `changeClient` already applies for the same reason.
     */
    @Transactional
    fun changePackagingUnit(id: Long, packagingUnitId: Long?, tenant: TenantContext): StockUnit {
        val su = findByIdForWrite(id, tenant)
        requireReclassifiable(su, packagingUnitId)
        val old = su.packagingUnitId
        su.packagingUnitId = packagingUnitId

        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = su,
            tenant = tenant,
            toUnitLoad = su.unitLoad.labelId,
            toLocation = su.unitLoad.storageLocationName,
            activityCode = "PACKAGING_UNIT_CHANGE",
        )
        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "PackagingUnitChanged",
            payload = PackagingUnitChangedEvent(
                entityType = "StockUnit",
                entityId = su.id!!,
                oldPackagingUnitId = old,
                newPackagingUnitId = packagingUnitId,
                locationId = su.unitLoad.storageLocationId,
            ),
            tenantId = su.clientId,
        )
        return su
    }

    private fun requireReclassifiable(su: StockUnit, packagingUnitId: Long?) {
        if (su.state >= StockState.PICKED.code) {
            throw InventoryException.InvalidTarget(
                "stock unit ${su.id} is at state ${su.state}; packaging unit cannot be reclassified at or past PICKED",
            )
        }
        val puId = packagingUnitId ?: return
        val pu = packagingUnitLookup.findById(puId)
        if (pu == null || pu.itemDataId != su.itemDataId) {
            throw InventoryException.ValidationFailed(
                if (pu == null) {
                    "unknown packaging unit $puId"
                } else {
                    "packaging unit $puId does not belong to item ${su.itemDataId}"
                },
            )
        }
    }

    /**
     * Soft-deletes [id] (flips `state` to [StockState.DELETABLE], never a physical row removal;
     * see [DefaultStockCountingPort.applyCount]'s KDoc for why a hard delete here is not an
     * option).
     *
     * Deliberately does NOT call [UnitLoadTerminator.trashIfEmpty] itself, even though this is
     * the one place every soft-delete funnels through (REST `DELETE /stock-units/{id}`,
     * [DefaultStockCountingPort.applyCount]'s zero-count branch, and
     * [DefaultStockReceiver.unreceive]). [DefaultStockReceiver.unreceive] is a goods-receiving
     * CORRECTION, not a retirement: `AsnUlAdviceService.reopen`'s KDoc documents the receiving
     * workflow deliberately re-receiving onto the SAME unit load (same label) after a reversal,
     * which an auto-flip to DELETABLE here would permanently block (labels are globally unique,
     * so a terminal unit load's label can never be reused). The two callers for whom "empty ==
     * retired" genuinely holds -- the REST endpoint and the cycle-count zero-branch -- call
     * [UnitLoadTerminator.trashIfEmpty] themselves right after this returns (task-10,
     * defect-burndown-4, row 14).
     */
    @Transactional
    fun deleteStock(id: Long, tenant: TenantContext): StockUnit {
        val su = findByIdForWrite(id, tenant)
        su.state = StockState.DELETABLE.code
        // Review fix (defect-burndown-5, Task 1 follow-up): same gap as changeState() had --
        // going DELETABLE via delete/zero-count is a state change too, so it must stamp
        // modified explicitly for StockPurgeService's retention window to mean anything here.
        su.modified = Instant.now()
        weightCalculator.recalculate(su.unitLoad) // Row 16: DELETABLE stock stops counting.

        journalService.record(
            recordType = JournalRecordType.DELETED,
            stockUnit = su,
            tenant = tenant,
            amount = su.amount,
            fromUnitLoad = su.unitLoad.labelId,
            fromLocation = su.unitLoad.storageLocationName,
        )

        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = su.id!!,
            eventType = "Deleted",
            payload = StockUnitDeletedEvent(
                stockUnitId = su.id!!,
                itemDataId = su.itemDataId,
                itemDataNumber = su.itemDataNumber,
                amount = su.amount,
                locationId = su.unitLoad.storageLocationId,
            ),
            tenantId = su.clientId,
        )

        return su
    }

    /** [UnitLoadTerminator.trashIfEmpty], scoped for [StockUnit]'s own callers so the REST
     * endpoint and [DefaultStockCountingPort.applyCount] don't each reach past this class into
     * `unitLoadTerminator` and `su.unitLoad.id`/`su.clientId` themselves. */
    fun trashUnitLoadIfEmpty(su: StockUnit, tenant: TenantContext, activityCode: String?) {
        unitLoadTerminator.trashIfEmpty(su.unitLoad.id!!, su.clientId, tenant, activityCode)
    }

    @Transactional
    fun reserveStock(
        id: Long,
        amount: BigDecimal,
        correlationId: String,
        tenant: TenantContext,
    ): ReserveStockResponse {
        val su = findByIdForWrite(id, tenant)

        if (su.lockType != LockType.UNLOCKED.code) {
            throw InventoryException.StockLocked(su.id!!, su.lockType)
        }
        if (su.availableAmount < amount) {
            throw InventoryException.InsufficientStock(su.availableAmount, amount)
        }

        val oldAmount = su.reservedAmount
        su.reservedAmount = su.reservedAmount.add(amount)

        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = su,
            tenant = tenant,
            amount = amount,
            toUnitLoad = su.unitLoad.labelId,
            toLocation = su.unitLoad.storageLocationName,
            correlationId = correlationId,
        )

        stockAmountEventPublisher.publish(su, "RESERVE")

        return ReserveStockResponse(
            stockUnitId = su.id!!,
            reservedAmount = su.reservedAmount,
            newAvailableAmount = su.availableAmount,
        )
    }

    /**
     * Refuses to release more than is actually reserved, rather than clamping to zero. A silent
     * clamp let a caller's over-release (a divergent [amount]) walk `reservedAmount` past zero and
     * consume a third party's live reservation on the same stock unit without any signal — the
     * root cause behind defect I1 (a delivery-order cancel silently stealing another order's
     * reservation). Refusing also keeps the journal truthful by construction: it used to record
     * the *requested* release amount even when the applied delta was smaller (clamped).
     */
    @Transactional
    fun releaseReservation(
        id: Long,
        amount: BigDecimal,
        correlationId: String,
        tenant: TenantContext,
    ) {
        val su = findByIdForWrite(id, tenant)
        if (amount > su.reservedAmount) {
            throw InventoryException.InsufficientStock(su.reservedAmount, amount)
        }
        su.reservedAmount = su.reservedAmount.subtract(amount)

        journalService.record(
            recordType = JournalRecordType.CHANGED,
            stockUnit = su,
            tenant = tenant,
            amount = amount.negate(),
            toUnitLoad = su.unitLoad.labelId,
            toLocation = su.unitLoad.storageLocationName,
            correlationId = correlationId,
        )

        stockAmountEventPublisher.publish(su, "RELEASE")
    }

    @Transactional
    fun transferStock(
        id: Long,
        targetUnitLoadId: Long,
        amount: BigDecimal,
        activityCode: String,
        tenant: TenantContext,
    ): StockUnit {
        val source = findByIdForWrite(id, tenant)
        val targetUL = unitLoadForWrite(targetUnitLoadId, tenant)
        requireSameOwnerForTransfer(source, targetUL)

        if (amount <= BigDecimal.ZERO) {
            throw InventoryException.ValidationFailed("transfer amount must be positive")
        }

        if (source.availableAmount < amount) {
            throw InventoryException.InsufficientStock(source.availableAmount, amount)
        }

        val targetStock = resolveTransferTargetStock(source, targetUL, amount, activityCode)

        // Reduce source
        val oldAmount = source.amount
        source.amount = source.amount.subtract(amount)

        journalService.record(
            recordType = JournalRecordType.TRANSFERRED,
            stockUnit = source,
            tenant = tenant,
            amount = amount,
            fromUnitLoad = source.unitLoad.labelId,
            fromLocation = source.unitLoad.storageLocationName,
            toUnitLoad = targetUL.labelId,
            toLocation = targetUL.storageLocationName,
            activityCode = activityCode,
        )

        outboxService.publish(
            aggregateType = "StockUnit",
            aggregateId = source.id!!,
            eventType = "AmountChanged",
            payload = StockUnitAmountChangedEvent(
                stockUnitId = source.id!!,
                itemDataId = source.itemDataId,
                itemDataNumber = source.itemDataNumber,
                oldAmount = oldAmount,
                newAmount = source.amount,
                changeAmount = amount.negate(),
                recordType = JournalRecordType.TRANSFERRED.code,
                activityCode = activityCode,
                locationId = source.unitLoad.storageLocationId,
                locationName = source.unitLoad.storageLocationName,
            ),
            // The source's owner: every field here is source-derived (the target is deliberately
            // not reported), and the D1 cross-owner guard above guarantees source/target share
            // an owner anyway, so this attribution can't silently diverge from the target's.
            tenantId = source.clientId,
        )

        // If source is empty, mark deletable and let the terminator check whether the whole
        // source unit load just went empty too (task-10, defect-burndown-4, row 14).
        if (source.amount.compareTo(BigDecimal.ZERO) == 0) {
            source.state = StockState.DELETABLE.code
            // Review fix (defect-burndown-5, Task 1 follow-up): same gap as changeState() had --
            // going DELETABLE via an emptying transfer is a state change too, so it must stamp
            // modified explicitly for StockPurgeService's retention window to mean anything here.
            source.modified = Instant.now()
            unitLoadTerminator.trashIfEmpty(source.unitLoad.id!!, source.clientId, tenant, activityCode)
        }
        // Row 16: both sides of the move changed what is on them.
        listOf(source.unitLoad, targetUL).distinct().forEach(weightCalculator::recalculate)
        return targetStock
    }

    companion object {
        val SORTABLE_FIELDS = setOf("id", "itemDataNumber", "amount", "state", "created")
    }
}

/** Container for paginated entity results, letting the Resource do DTO mapping. */
data class PaginatedEntities<T>(val content: List<T>, val totalElements: Long)

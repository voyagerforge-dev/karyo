package com.karyo.inventory.service

import com.karyo.auth.spi.ClientLookup
import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.dto.CreateUnitLoadRequest
import com.karyo.inventory.api.event.LockChangedEvent
import com.karyo.inventory.api.event.UnitLoadClientChangedEvent
import com.karyo.inventory.api.event.UnitLoadTransferredEvent
import com.karyo.inventory.api.spi.OpenPickGuard
import com.karyo.inventory.api.vo.JournalRecordType
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.security.PrincipalKind
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import com.karyo.security.writeScope
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.exception.InventoryException
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.inventory.repository.UnitLoadTypeRepository
import com.karyo.layout.spi.ClearingLocationLookup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Event
import jakarta.transaction.Transactional

/** Hop bound on a carrier chain walk — a guard against a cycle that already exists in the data. */
private const val MAX_CARRIER_DEPTH = 100

@ApplicationScoped
class UnitLoadService(
    private val unitLoadRepository: UnitLoadRepository,
    private val unitLoadTypeRepository: UnitLoadTypeRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val outboxService: OutboxService,
    private val transferredEvent: Event<UnitLoadTransferredEvent>,
    private val journalService: JournalService,
    private val clientLookup: ClientLookup,
    private val openPickGuard: OpenPickGuard,
    private val stockService: StockService,
    private val clearingLocationLookup: ClearingLocationLookup,
    private val unitLoadTerminator: UnitLoadTerminator,
) {
    fun findById(id: Long, tenant: TenantContext): UnitLoad {
        val ul = unitLoadRepository.findById(id)
            ?: throw InventoryException.NotFound("UnitLoad", id)
        if (!tenant.readScope().permits(ul.clientId))
            throw InventoryException.NotFound("UnitLoad", id)
        return ul
    }

    fun findByLabelId(labelId: String, tenant: TenantContext): UnitLoad {
        val ul = unitLoadRepository.findByLabelId(labelId)
            ?: throw InventoryException.NotFound("UnitLoad", labelId)
        if (!tenant.readScope().permits(ul.clientId))
            throw InventoryException.NotFound("UnitLoad", labelId)
        return ul
    }

    /**
     * Load for mutation paths, owner-blind for an ops principal only — see
     * [StockService.findByIdForWrite]. Attribution (the row's `clientId`) is never
     * reassigned by callers, with exactly one sanctioned exception: [changeClient], an
     * OPS-only operation that refuses on any encumbrance and journals the move as paired
     * DELETED/CREATED rows under the old and new owner.
     *
     * Acquires PESSIMISTIC_WRITE — callers are mutation paths inside `@Transactional`; do NOT
     * use for read endpoints. Every mutating path in this service (`changeClient`, `setCarrier`,
     * `delete`, `transferToLocation`, `transferToCarrier`, `lock`, `unlock`) funnels through this
     * one choke point, so its check-then-act guards (setCarrier's busy-check, changeClient's
     * assertUnencumbered, transferToCarrier's isCarrier read) run against a row locked for the
     * duration of the transaction, not an unlocked READ COMMITTED snapshot. Note:
     * [applyLockRecursive]'s carrier children are written under the ROOT's lock only — children
     * loaded via [UnitLoadRepository.findByCarrierUnitLoadId] are not individually locked, which
     * is acceptable because every path that re-parents a child locks the parent first (see
     * [transferToCarrier]).
     */
    fun findByIdForWrite(id: Long, tenant: TenantContext): UnitLoad {
        val ul = unitLoadRepository.findByIdForUpdate(id)
            ?: throw InventoryException.NotFound("UnitLoad", id)
        if (!tenant.writeScope().permits(ul.clientId)) {
            throw InventoryException.NotFound("UnitLoad", id)
        }
        return ul
    }

    fun findByLocation(locationId: Long, tenant: TenantContext): List<UnitLoad> {
        val scope = tenant.readScope()
        return unitLoadRepository.findByStorageLocationId(locationId)
            .filter { scope.permits(it.clientId) }
    }

    /**
     * S6 (outbound-completion task-8): appends `"-" + id` to [id]'s `labelId`, idempotent via
     * `endsWith` -- a second call is a no-op. Backing method for
     * [com.karyo.inventory.api.spi.UnitLoadMover.appendDispatchSuffix], called from
     * `ShippingService.dispatch`'s per-UL loop when the `karyo.shipping.rename-unit-load` runtime
     * property is on for the shipment's client. No state guard: unlike [transferToLocation], a
     * label rename does not move anything, so it is deliberately allowed even on a unit load the
     * SAME dispatch call is about to (or already did) drive DELETABLE via `shipContainer`.
     */
    @Transactional
    fun renameForDispatch(id: Long, tenant: TenantContext): String {
        val ul = findByIdForWrite(id, tenant)
        val suffix = "-$id"
        if (!ul.labelId.endsWith(suffix)) {
            ul.labelId = ul.labelId + suffix
        }
        return ul.labelId
    }

    @Transactional
    fun create(request: CreateUnitLoadRequest, tenant: TenantContext): UnitLoad {
        val ownerClientId = resolveOwner(request.clientId, tenant)
        val ult = unitLoadTypeRepository.findById(request.unitLoadTypeId)
            ?: throw InventoryException.NotFound("UnitLoadType", request.unitLoadTypeId)
        val ul = UnitLoad().apply {
            clientId = ownerClientId
            labelId = request.labelId
            externalId = request.externalId
            unitLoadType = ult
            storageLocationId = request.storageLocationId
            storageLocationName = request.storageLocationName
        }
        unitLoadRepository.persist(ul)
        return ul
    }

    /**
     * Resolves the goods owner for a new unit load.
     *
     * 1. An explicit non-zero `clientId` wins, provided it is within the caller's write scope —
     *    an [PrincipalKind.OPS] principal may name any owner (the point of owner-blind writes),
     *    an [PrincipalKind.OWNER] principal may only name itself.
     * 2. Explicit `clientId == 0L` is always rejected — 0 is the SYS tenant, never a real owner.
     * 3. A missing `clientId` falls back to the acting principal's own client, but only for an
     *    [PrincipalKind.OWNER] principal with a real (non-zero) client — a goods owner can only
     *    own its own goods, so this is an unambiguous inference, not a guess.
     * 4. A missing `clientId` for an [PrincipalKind.OPS] principal, or for any principal whose
     *    own client is 0 (SYS), is rejected — there is no ambient owner to infer.
     */
    private fun resolveOwner(requestClientId: Long?, tenant: TenantContext): Long {
        if (requestClientId != null) {
            return validateExplicitOwner(requestClientId, tenant)
        }
        if (tenant.principalKind == PrincipalKind.OPS) {
            throw InventoryException.ValidationFailed(
                "clientId is required for an ops principal — it has no ambient owner to infer",
            )
        }
        if (tenant.clientId == 0L) {
            throw InventoryException.ValidationFailed(
                "clientId must identify a goods owner; the acting principal has no client to infer from",
            )
        }
        return tenant.clientId
    }

    /**
     * Validates an explicitly-supplied owner (rules 1 and 2 of [resolveOwner]).
     *
     * Split out so [resolveOwner] stays within the project's `ThrowsCount` budget. The four
     * rejection paths are genuinely distinct and each deserves its own message, so the fix is
     * to group them, not to collapse them into a vaguer error.
     */
    private fun validateExplicitOwner(requestClientId: Long, tenant: TenantContext): Long {
        if (requestClientId == 0L) {
            throw InventoryException.ValidationFailed(
                "clientId must identify a goods owner; 0 (the SYS tenant) is not a valid owner",
            )
        }
        // An explicit owner must be within the caller's write scope. Ops staff may name any
        // goods owner -- that is the point of owner-blind writes. A goods-owner principal may
        // only name itself; accepting an arbitrary value would let it plant rows in another
        // customer's dataset.
        if (!tenant.writeScope().permits(requestClientId)) {
            throw InventoryException.ValidationFailed(
                "clientId $requestClientId is not permitted for this principal",
            )
        }
        return requestClientId
    }

    /**
     * Reassigns this unit load and ALL its stock to goods owner [targetClientId] — the one
     * sanctioned exception to [findByIdForWrite]'s "attribution is never reassigned by
     * callers" invariant -- behavioral parity with myWMS `InventoryBusiness.changeClient` +
     * `checkChangeClient`.
     *
     * OPS-only, and the kind gate runs BEFORE any row load: this is the first hard
     * [PrincipalKind] check in this module outside [resolveOwner], and its position means a
     * goods-owner principal gets a uniform 403 for every id — real or not — so the endpoint
     * is not an existence oracle. The operation is total: it refuses (409) on any
     * encumbrance — a reservation, a non-terminal pick, or stock at or past PICKED — before
     * writing anything, so it
     * either fully succeeds or changes nothing. The myWMS client-owned-product guard is
     * deferred pending product-owner lookup semantics (see the worklist note).
     */
    @Transactional
    fun changeClient(id: Long, targetClientId: Long, activityCode: String?, tenant: TenantContext): UnitLoad {
        // OPS gate FIRST — before any row load, so a non-OPS principal learns nothing about
        // whether the unit load even exists.
        if (tenant.principalKind != PrincipalKind.OPS) {
            throw InventoryException.Forbidden("changeClient is restricted to operating-company (OPS) principals")
        }

        val ul = findByIdForWrite(id, tenant)
        val stockUnits = stockUnitRepository.findByUnitLoadId(ul.id!!)

        validateTargetOwner(targetClientId)
        if (ul.clientId == targetClientId) {
            // myWMS parity: a same-owner change is a silent no-op — no journals, no events.
            return ul
        }
        assertUnencumbered(id, stockUnits)

        val oldClientId = ul.clientId
        stockUnits.forEach { cascadeStockOwner(it, targetClientId, ul, activityCode, tenant) }
        // The unit load itself moves LAST: a failure anywhere in the cascade must never leave
        // a reassigned load still carrying the old owner's stock.
        ul.clientId = targetClientId

        val event = UnitLoadClientChangedEvent(
            unitLoadId = ul.id!!,
            labelId = ul.labelId,
            oldClientId = oldClientId,
            newClientId = targetClientId,
            stockUnitCount = stockUnits.size,
        )
        // Published TWICE, once under each owner: WebhookFanoutScheduler matches a
        // subscription by `s.clientId == e.tenantId`, and both the owner whose goods leave
        // and the owner whose goods arrive deserve to see this event — a single row could
        // only ever notify one of them.
        listOf(oldClientId, targetClientId).forEach { owner ->
            outboxService.publish(
                aggregateType = "UnitLoad",
                aggregateId = ul.id!!,
                eventType = "UnitLoadClientChanged",
                payload = event,
                tenantId = owner,
            )
        }
        return ul
    }

    /**
     * [changeClient] target checks: the SYS tenant is not a goods owner, the client must
     * exist, and (via [requireActiveTarget]) it must be a live counterparty, not a retired one.
     */
    private fun validateTargetOwner(targetClientId: Long) {
        if (targetClientId == 0L) {
            throw InventoryException.InvalidTarget(
                "targetClientId must identify a goods owner; 0 (the SYS tenant) is not one",
            )
        }
        if (!clientLookup.exists(targetClientId)) {
            throw InventoryException.InvalidTarget(
                "targetClientId $targetClientId does not identify an existing client",
            )
        }
        requireActiveTarget(targetClientId)
    }

    /**
     * Third [validateTargetOwner] check, split out so that function stays within the
     * project's `ThrowsCount` budget (same reason [validateExplicitOwner] is split from
     * [resolveOwner]): an existing client row is not enough — [changeClient] must refuse a
     * retired (INACTIVE) target, per [ClientLookup.isActive].
     */
    private fun requireActiveTarget(targetClientId: Long) {
        if (!clientLookup.isActive(targetClientId)) {
            throw InventoryException.InvalidTarget(
                "targetClientId $targetClientId identifies a retired (INACTIVE) client — " +
                    "reactivate it before reassigning goods to it",
            )
        }
    }

    /**
     * [changeClient] refuses, never migrates, an encumbrance — three signals, all checked
     * before anything is written, keeping the operation total:
     *
     * 1. A reservation on any stock unit.
     * 2. A non-terminal pick still referencing one ([OpenPickGuard] — asked via the SPI, whose
     *    implementation owns the empty-input guard and the terminal-state set).
     * 3. Any stock unit already at or past [StockState.PICKED] — in-flight or terminal goods.
     *    A pick reaching PICKED clears its reservation, so by the time picking finishes signal
     *    1 no longer fires; [OpenPickGuard] treats PICKED as terminal on purpose (it answers
     *    "is a pick still open", not "did stock ever move"), so signal 2 doesn't fire either.
     *    Without this state check those two facts combine into a hole: a UL carrying
     *    PICKED/PACKED/SHIPPED stock could be reassigned to a new owner, moving fulfilled goods
     *    and writing a CREATED journal row into the new owner's ledger for goods it never
     *    received. This is also where Karyo deliberately EXCEEDS myWMS parity: legacy
     *    `checkChangeClient` refused when a PICKED picking line referenced the stock, a
     *    coverage that our terminal-set choice in [OpenPickGuard] otherwise inverts — this
     *    check restores it. DELETABLE(1000) is included by `>=`: a soft-deleted unit still
     *    riding the load is anomalous, and refusing is the conservative call.
     *
     *    [StockUnit.lockType] is deliberately NOT checked here — a QA hold travels with the
     *    goods legitimately and is not itself an encumbrance on ownership.
     */
    private fun assertUnencumbered(unitLoadId: Long, stockUnits: List<StockUnit>) {
        if (stockUnits.any { it.reservedAmount.signum() > 0 }) {
            throw InventoryException.Encumbered(
                "UnitLoad $unitLoadId has reserved stock; release the reservations before changing owner",
            )
        }
        if (openPickGuard.hasOpenPicks(stockUnits.mapNotNull { it.id })) {
            throw InventoryException.Encumbered(
                "UnitLoad $unitLoadId has stock referenced by open picks; complete or cancel them first",
            )
        }
        assertNoInFlightOrTerminalStock(unitLoadId, stockUnits)
    }

    /**
     * Third [assertUnencumbered] signal, split out so that function stays within the project's
     * `ThrowsCount` budget (same reason [validateExplicitOwner] is split from [resolveOwner]).
     * See [assertUnencumbered]'s KDoc for the full rationale.
     */
    private fun assertNoInFlightOrTerminalStock(unitLoadId: Long, stockUnits: List<StockUnit>) {
        if (stockUnits.any { it.state >= StockState.PICKED.code }) {
            throw InventoryException.Encumbered(
                "UnitLoad $unitLoadId has stock in an in-flight or terminal state; " +
                    "it cannot be reassigned to a new owner",
            )
        }
    }

    /**
     * Moves one stock unit to [targetClientId] with the paired journal rows of myWMS's
     * `recordChangeClient`. [JournalService.record] attributes each row from
     * `stockUnit.clientId` AT CALL TIME, so the ordering IS the correctness: the DELETED row
     * is written while the stock still carries the OLD owner (goods leave that owner's
     * ledger), the CREATED row after the reassignment (goods arrive in the NEW owner's).
     * Zero-amount stock is cascaded but not journaled (myWMS parity — nothing moved).
     */
    private fun cascadeStockOwner(
        su: StockUnit,
        targetClientId: Long,
        ul: UnitLoad,
        activityCode: String?,
        tenant: TenantContext,
    ) {
        val journaled = su.amount.signum() > 0
        if (journaled) {
            journalService.record(
                recordType = JournalRecordType.DELETED,
                stockUnit = su,
                tenant = tenant,
                amount = su.amount,
                fromUnitLoad = ul.labelId,
                fromLocation = ul.storageLocationName,
                activityCode = activityCode,
            )
        }
        su.clientId = targetClientId
        if (journaled) {
            journalService.record(
                recordType = JournalRecordType.CREATED,
                stockUnit = su,
                tenant = tenant,
                amount = su.amount,
                toUnitLoad = ul.labelId,
                toLocation = ul.storageLocationName,
                activityCode = activityCode,
            )
        }
    }

    /**
     * Designates (or un-designates) a unit load as a carrier. Metadata flag only —
     * no stock moves, so no journal row and no outbox event. Un-designating is
     * refused while other unit loads still sit on this carrier, which would strand
     * transferToCarrier's invariant that a parent is always a carrier.
     */
    @Transactional
    fun setCarrier(id: Long, isCarrier: Boolean, tenant: TenantContext): UnitLoad {
        val ul = findByIdForWrite(id, tenant)
        if (!isCarrier && unitLoadRepository.findByCarrierUnitLoadId(id).isNotEmpty()) {
            throw InventoryException.ValidationFailed(
                "UnitLoad $id still carries other unit loads",
            )
        }
        ul.isCarrier = isCarrier
        return ul
    }

    /**
     * Hard-deletes a unit load with no stock and no carried children.
     *
     * The carrier check (row 15, task-10, defect-burndown-4) runs BEFORE the trio fires and
     * before the row is removed: without it, deleting a carrier still carrying a child hit the
     * plain `unit_loads_carrier_unit_load_id_fkey` FK at the database and surfaced as a raw 500
     * instead of the same `has-dependents` 409 the stock-units guard above it already gives.
     */
    @Transactional
    fun delete(id: Long, tenant: TenantContext) {
        val ul = findByIdForWrite(id, tenant)
        val stockCount = stockUnitRepository.findByUnitLoadId(ul.id!!).size
        if (stockCount > 0) {
            throw InventoryException.HasDependents("UnitLoad", id, "stock units")
        }
        if (unitLoadRepository.findByCarrierUnitLoadId(id).isNotEmpty()) {
            throw InventoryException.HasDependents("UnitLoad", id, "carried unit loads")
        }

        unitLoadTerminator.fireTrashed(ul, emptyList(), activityCode = null, tenant)

        unitLoadRepository.deleteById(id)
    }

    /**
     * DELETABLE-target guard (task-10, defect-burndown-4, row 14, same idiom as
     * [StockService]'s `requireUnitLoadNotDeleted`): a unit load a [UnitLoadTerminator] flip
     * already marked terminal must refuse to move: moving it around the warehouse would leave a
     * ghost pallet occupying a fresh location's allocation while the UL itself claims to be gone.
     */
    @Transactional
    fun transferToLocation(id: Long, destinationLocationId: Long, destinationLocationName: String, tenant: TenantContext): UnitLoad {
        val ul = findByIdForWrite(id, tenant)
        if (ul.state == StockState.DELETABLE.code) {
            throw InventoryException.InvalidStateTransition(id, ul.state, ul.state)
        }
        val fromLocationId = ul.storageLocationId
        val fromLocationName = ul.storageLocationName
        ul.storageLocationId = destinationLocationId
        ul.storageLocationName = destinationLocationName

        val event = UnitLoadTransferredEvent(
            unitLoadId = ul.id!!,
            labelId = ul.labelId,
            fromLocationId = fromLocationId,
            fromLocationName = fromLocationName,
            toLocationId = destinationLocationId,
            toLocationName = destinationLocationName,
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

        return ul
    }

    /**
     * Places [id] onto the carrier unit load [carrierUnitLoadId] — a pallet onto a truck or dolly.
     *
     * The moved unit load takes the carrier's storage location: physically it is now wherever the
     * carrier is. That is why this fires [UnitLoadTransferredEvent] like an ordinary location move —
     * `karyo-layout` maintains location occupancy from that event, and omitting it would silently
     * leave occupancy wrong.
     *
     * Both rows are locked via [findByIdForWrite] in deterministic ASCENDING-ID order before any
     * role (`ul` vs `carrier`) is assigned — two concurrent reciprocal transfers (A onto B and B
     * onto A) must acquire their two row locks in the same order or they deadlock. Roles are
     * assigned only after both locks are held; every guard below runs unchanged against the
     * now-locked rows.
     */
    @Transactional
    fun transferToCarrier(id: Long, carrierUnitLoadId: Long, tenant: TenantContext): UnitLoad {
        if (id == carrierUnitLoadId) {
            throw InventoryException.ValidationFailed("A unit load cannot be its own carrier")
        }

        // Deterministic lock order (ascending id) — two concurrent reciprocal transfers must
        // not deadlock. Roles are assigned AFTER both rows are locked.
        val loaded = listOf(id, carrierUnitLoadId).sorted().associateWith { findByIdForWrite(it, tenant) }
        val ul = loaded.getValue(id)
        val carrier = loaded.getValue(carrierUnitLoadId)

        if (!carrier.isCarrier) {
            throw InventoryException.ValidationFailed(
                "UnitLoad $carrierUnitLoadId is not a carrier",
            )
        }
        requireSameOwnerForCarrier(ul, carrier)
        assertNoCarrierCycle(startingAt = carrier, mustNotReach = id)

        val fromLocationId = ul.storageLocationId
        val fromLocationName = ul.storageLocationName
        ul.carrierUnitLoad = carrier
        ul.storageLocationId = carrier.storageLocationId
        ul.storageLocationName = carrier.storageLocationName

        val event = UnitLoadTransferredEvent(
            unitLoadId = ul.id!!,
            labelId = ul.labelId,
            fromLocationId = fromLocationId,
            fromLocationName = fromLocationName,
            toLocationId = carrier.storageLocationId,
            toLocationName = carrier.storageLocationName,
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

        return ul
    }

    /**
     * Integrity, not authorization (D1 precedent, [StockService.requireSameOwnerForTransfer]):
     * an OPS principal passes both write-scope checks in [transferToCarrier] for ANY two owners,
     * so without this comparison the endpoint itself constructs the mixed cross-owner trees the
     * lock cascade has to defend against (WORKLIST F3). Split out from [transferToCarrier] to
     * keep it under detekt's `ThrowsCount` limit, matching the [requireSameOwnerForTransfer]
     * precedent for the same shape of extraction.
     */
    private fun requireSameOwnerForCarrier(ul: UnitLoad, carrier: UnitLoad) {
        if (ul.clientId != carrier.clientId) {
            throw InventoryException.CrossOwner(
                "cannot nest a unit load owned by client ${ul.clientId} under a carrier owned " +
                    "by client ${carrier.clientId} — use change-client to reassign ownership first",
            )
        }
    }

    /**
     * Rejects a carrier chain that would loop. The hop bound is a guard against a cycle that
     * already exists in the data: without it a corrupt chain would spin here rather than fail.
     */
    private fun assertNoCarrierCycle(startingAt: UnitLoad, mustNotReach: Long) {
        var current: UnitLoad? = startingAt
        var hops = 0
        while (current != null) {
            if (current.id == mustNotReach) {
                throw InventoryException.ValidationFailed(
                    "Cannot place unit load $mustNotReach on carrier ${startingAt.id}: it would form a cycle",
                )
            }
            if (++hops > MAX_CARRIER_DEPTH) {
                throw InventoryException.ValidationFailed("Carrier chain exceeds $MAX_CARRIER_DEPTH levels")
            }
            current = current.carrierUnitLoad
        }
    }

    /**
     * A2-3 recursive unit-load lock (myWMS `addUnitLoadLockRecursive`, `LockType.GENERAL`
     * semantics only — the legacy CLEARING lock values are dead and were never carried over).
     * Locks this unit load, all its stock units, and all nested carrier children (unit
     * loads whose [UnitLoad.carrierUnitLoad] points at this one, walked downward and
     * depth-capped by [MAX_CARRIER_DEPTH] as a guard against pathological/cyclic data —
     * the primary cycle defense is [assertNoCarrierCycle] at [transferToCarrier] time).
     * D3/F1 (2026-07-25): enforcement is now a READ-TIME INVARIANT — `findForSelection`
     * joins `unitLoad.lockType = 0` directly, so a locked pallet is unpickable even for
     * stock that lands on it AFTER this call (late receive, transfer, carrier nesting).
     * The stock-unit cascade below is no longer the sole enforcement; it remains for
     * journaling and per-stock visibility. Idempotent: a unit load or stock
     * unit already at the target lock is left untouched — no duplicate journal or
     * outbox rows. F2 fix: a stock unit that already carries a DIFFERENT, individually-set
     * non-zero lock (e.g. an expiry/QA hold from receipt) is left untouched rather than
     * overwritten with this call's code — see [applyStockLock] for the full rationale,
     * including why this matters for the later [unlock].
     */
    @Transactional
    fun lock(id: Long, lockType: Int, note: String?, tenant: TenantContext): UnitLoad {
        // Validated without LockType.fromCode's throw so an unknown code maps to 400
        // (InventoryException.ValidationFailed), not the mapper-less 500 an
        // IllegalArgumentException would otherwise fall through to.
        val target = LockType.entries.firstOrNull { it.code == lockType }
            ?: throw InventoryException.ValidationFailed("Unknown lock type: $lockType")
        if (target == LockType.UNLOCKED) {
            throw InventoryException.ValidationFailed("use unlock to clear a lock")
        }
        return applyLockRecursive(findByIdForWrite(id, tenant), target, note, tenant, depth = 0)
    }

    /**
     * Clears [lock] recursively — same cascade, target [LockType.UNLOCKED]. F2 fix: only
     * clears a stock unit whose lock equals the code THIS unit load itself carried right
     * before the unlock — a stock unit under a different, individually-set code (never
     * touched by the earlier [lock] call per its own preservation rule) survives the
     * unlock untouched. See [applyStockLock] for the full rationale and the one
     * documented, accepted ambiguity.
     */
    @Transactional
    fun unlock(id: Long, tenant: TenantContext): UnitLoad =
        applyLockRecursive(findByIdForWrite(id, tenant), LockType.UNLOCKED, note = null, tenant = tenant, depth = 0)

    /**
     * A2-1 transferToClearing (behavioral parity with myWMS's transfer-to-clearing, UnitLoad
     * overload only — Karyo deliberately diverges by not implementing the StockUnit
     * overload, §2.5):
     * move the pallet to the clearing singleton, then recursive GENERAL lock.
     */
    @Transactional
    fun transferToClearing(id: Long, note: String?, tenant: TenantContext): UnitLoad {
        val clearing = clearingLocationLookup.findClearing()
            ?: throw InventoryException.NotConfigured("no clearing location is configured (set isClearing on a location)")
        transferToLocation(id, clearing.id, clearing.name, tenant)
        return lock(id, LockType.GENERAL.code, note, tenant)
    }

    private fun applyLockRecursive(
        ul: UnitLoad,
        target: LockType,
        note: String?,
        tenant: TenantContext,
        depth: Int,
    ): UnitLoad {
        if (depth > MAX_CARRIER_DEPTH) {
            throw InventoryException.ValidationFailed("Carrier chain exceeds $MAX_CARRIER_DEPTH levels")
        }
        val old = ul.lockType
        if (old != target.code) {
            ul.lockType = target.code
            outboxService.publish(
                aggregateType = "UnitLoad",
                aggregateId = ul.id!!,
                eventType = "LockChanged",
                payload = LockChangedEvent(
                    entityType = "UnitLoad",
                    entityId = ul.id!!,
                    oldLock = old,
                    newLock = target.code,
                    locationId = ul.storageLocationId,
                ),
                tenantId = ul.clientId,
            )
        }
        stockUnitRepository.findByUnitLoadId(ul.id!!).forEach { su ->
            applyStockLock(su, target, ulLockBeforeChange = old, tenant, note)
        }
        unitLoadRepository.findByCarrierUnitLoadId(ul.id!!).forEach { child ->
            // Second-entity scoping (same invariant as transferToCarrier/unitLoadForWrite):
            // the subject (`ul`, checked by findByIdForWrite at the top of lock()/unlock())
            // is not the only entity this mutation writes -- every nested carrier child gets
            // its lockType written too, so each one must independently clear the caller's
            // write scope. Without this, an OWNER locking a carrier it legitimately owns could
            // write lockType on another owner's unit load simply by it being nested underneath
            // -- NotFound (not Forbidden) matches findByIdForWrite so existence does not leak,
            // and throwing here rolls back the whole transaction, including the carrier's own
            // lock write already applied above.
            if (!tenant.writeScope().permits(child.clientId)) {
                throw InventoryException.NotFound("UnitLoad", child.id!!)
            }
            applyLockRecursive(child, target, note, tenant, depth + 1)
        }
        return ul
    }

    /**
     * Cascades this unit-load lock/unlock onto ONE stock unit, without clobbering a
     * pre-existing, individually-set per-stock lock that differs from the unit load's own
     * code (F2 fix — before this, `unlock` unconditionally drove every stock unit on the
     * pallet to UNLOCKED, destroying a hold like [LockType.LOT_EXPIRED]/[LockType.QUALITY_FAULT]
     * set directly on the stock at receipt; expired stock then silently re-entered selection).
     *
     * The two directions are asymmetric on purpose:
     * - **LOCK** ([target] != UNLOCKED): a stock unit already carrying a DIFFERENT non-zero
     *   lock is left untouched — it is already locked, which is the pallet-lock's goal, and
     *   overwriting it with the pallet's own code would let a later pallet-unlock silently
     *   clear the stronger hold (see below). Only a currently-unlocked stock unit is driven
     *   to the pallet's code.
     * - **UNLOCK** ([target] == UNLOCKED): only a stock unit whose lock EQUALS
     *   [ulLockBeforeChange] — the unit load's OWN code immediately before this call applied
     *   UNLOCKED — is cleared. A stock unit under any other code was never driven by this
     *   unit-load lock (either it predates it, per the LOCK rule above, or was set by some
     *   other path since) and must survive the unlock untouched.
     *
     * Documented ambiguity, accepted rather than solved: if a stock unit was individually
     * locked with the SAME code the unit load itself uses (today always
     * [LockType.GENERAL] — [lock] accepts no other target), the two are indistinguishable
     * here and the per-stock lock is cleared along with the unit-load lock on unlock. This is
     * acceptable because GENERAL carries no domain-specific reason worth preserving, unlike
     * LOT_EXPIRED/QUALITY_FAULT.
     */
    private fun applyStockLock(
        su: StockUnit,
        target: LockType,
        ulLockBeforeChange: Int,
        tenant: TenantContext,
        note: String?,
    ) {
        if (su.lockType == target.code) return
        if (target != LockType.UNLOCKED) {
            if (su.lockType != 0) return // preserve a different, already-nonzero per-stock lock
        } else {
            if (su.lockType != ulLockBeforeChange) return // not this unit-load lock's doing
        }
        stockService.setLock(su.id!!, target.code, tenant, reason = note)
    }
}

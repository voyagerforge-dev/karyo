package com.karyo.inventory.api.spi

import java.math.BigDecimal

/**
 * In-process pick contract (the StockReserver/UnitLoadMover sibling): moves stock onto a
 * pick container and transitions it to PICKED. Implemented by inventory-core and consumed
 * by the fulfillment module to execute a pick confirmation without a cross-service call.
 */
interface StockPicker {
    /**
     * Moves [amount] off [sourceStockUnitId] onto [targetUnitLoadId] (the pick container) and
     * sets the resulting picked stock to PICKED(600). Returns the target stock unit id.
     * Scoped to the current tenant by the implementation. Throws if the source has less than
     * [amount] available.
     */
    fun pickStock(sourceStockUnitId: Long, amount: BigDecimal, targetUnitLoadId: Long): Long

    /**
     * Creates an empty pick-container UnitLoad at a staging location (called once per PickOrder
     * at release). [clientId] is the goods owner named by the pick order — the container is
     * attributed to the work item's owner, not to the executing picker. [unitLoadTypeId] should
     * be an aggregating PICKING type (e.g. "Pick Bin") so multiple picks of the same SKU
     * accumulate. Returns the new UnitLoad id. Tenant comes from the current request context.
     */
    fun createPickContainer(
        clientId: Long,
        unitLoadTypeId: Long,
        locationId: Long,
        locationName: String,
        labelId: String,
    ): Long

    /**
     * Frees the reservation for an unpicked remainder on a short pick (the goods were reserved but
     * not physically present). Releases min(amount, reservedAmount); journaled. Tenant from context.
     */
    fun releaseUnpickedReservation(sourceStockUnitId: Long, amount: BigDecimal)

    /**
     * Explicit-`clientId` variant of [releaseUnpickedReservation] for trusted in-process SPI
     * callers whose ambient `TenantContext` cannot be relied on to already carry the right tenant
     * -- same doctrine as `StockReserver.reserve(request, clientId)`: a cross-module port invoked
     * directly (wave cancel, a `@Scheduled` sweep) bypasses REST/`TenantFilter`, so the request
     * scope is active but UNPRIMED and the ambient `clientId` defaults to 0. Scoped by [clientId],
     * never the ambient `TenantContext`.
     *
     * **A stock unit not owned by [clientId] throws
     * [com.karyo.inventory.exception.InventoryException.NotFound]**, releasing nothing -- the
     * contract inherited unchanged from `StockService.findByIdForWrite`, which reports a row its
     * write scope does not permit as absent rather than as a distinct "forbidden". This is
     * deliberately NOT softened to a silent no-op: a caller passing the wrong `clientId` is a bug
     * that would otherwise strand `reservedAmount` on the real owner's stock forever. Note the
     * ordering: a non-positive [amount] is a no-op that returns BEFORE ownership is resolved at
     * all, so it never throws regardless of [clientId].
     *
     * Journal rows written through this overload carry the ambient principal's username as
     * `operatorName` when a request primed one, and `"system"` otherwise (a `@Scheduled` tick, a
     * port-to-port call on an unprimed request scope). The explicit [clientId] fixes the SCOPE
     * only; attribution still follows whoever is actually on the request (see
     * `TenantContext.ownerScoped`).
     */
    fun releaseUnpickedReservation(sourceStockUnitId: Long, amount: BigDecimal, clientId: Long)

    /**
     * Packs a pick container: flips every PICKED(600) stock unit on [unitLoadId] to PACKED(650)
     * (journaled by the underlying state change). Returns the count flipped. A STATE CHANGE only —
     * the goods stay on the container; the move to the shipping dock happens at ship-confirm.
     * Tenant is read from the current request context.
     */
    fun packContainer(unitLoadId: Long): Int

    /**
     * Explicit-`clientId` variant of [packContainer] -- same doctrine as the explicit
     * [releaseUnpickedReservation] overload above: scoped by [clientId], never the ambient
     * `TenantContext`.
     *
     * **A unit load not owned by [clientId] flips nothing and returns 0**, no throw -- the
     * contract inherited unchanged from `StockService.findByUnitLoad`, which FILTERS by read
     * scope, so a foreign scope simply sees an empty unit load. Deliberately not hardened into a
     * throw: this method is already contracted to return "how many were in the source state", and
     * zero is its normal answer for a container with nothing to pack.
     *
     * Journal rows written through this overload carry the ambient principal's username as
     * `operatorName` when a request primed one, and `"system"` otherwise (a `@Scheduled` tick, a
     * port-to-port call on an unprimed request scope). The explicit [clientId] fixes the SCOPE
     * only; attribution still follows whoever is actually on the request (see
     * `TenantContext.ownerScoped`).
     */
    fun packContainer(unitLoadId: Long, clientId: Long): Int

    /**
     * Ships a pick container: flips every PACKED(650) stock unit on [unitLoadId] to SHIPPED(680)
     * (journaled). Returns the count flipped. A state change only — the physical dock move is done
     * separately via UnitLoadMover. Tenant from the current request context.
     *
     * NOTE: this SPI owns the pick container's full **pick -> pack -> ship** stock lifecycle
     * (createPickContainer/pickStock/packContainer/shipContainer); the name `StockPicker` is historical.
     */
    fun shipContainer(unitLoadId: Long): Int

    /**
     * S4 (outbound-completion sprint): reverses a pack, restoring stock ahead of a pre-manifest
     * shipment cancel/unit-removal. Flips every PACKED(650) stock unit on [unitLoadId] back to
     * PICKED(600), or to ON_STOCK(300) when [restoreToOnStock] is true (the ad-hoc-origin case --
     * there is no PickOrder for the goods to return to). Non-PACKED stock on the unit load is left
     * untouched. Returns the count flipped.
     *
     * **Deliberate forward-only exception.** [StockUnit][com.karyo.inventory.domain.model.StockUnit]
     * state is otherwise strictly forward-only (see `StockService.changeState`), which has no path
     * backward from PACKED. The implementation must NOT route through `changeState` -- it stays
     * forward-only and untouched -- and instead writes the entity's state field directly. One
     * InventoryJournal row (activityCode "UNPACK") is written per flipped stock unit, attributed
     * under the stock unit's own (entity-owner) `clientId`. Tenant is read from the current
     * request context.
     */
    fun unpackContainer(unitLoadId: Long, restoreToOnStock: Boolean): Int

    /**
     * Explicit-`clientId` variant of [unpackContainer] -- same doctrine as the explicit
     * [packContainer] overload above: scoped by [clientId], never the ambient `TenantContext`,
     * and a unit load not owned by [clientId] flips nothing and returns 0 (no throw), because
     * `StockService.findByUnitLoad` filters by read scope rather than refusing. The journal rows
     * stay attributed to the stock unit's own (entity-owner) `clientId`, exactly as the ambient
     * overload writes them, and their `operatorName` is the ambient principal's username when a
     * request primed one, `"system"` otherwise (see `TenantContext.ownerScoped`): the explicit
     * [clientId] fixes the scope, not the attribution.
     */
    fun unpackContainer(unitLoadId: Long, restoreToOnStock: Boolean, clientId: Long): Int

    /**
     * S5 (outbound-completion sprint): packs an AD-HOC shipping unit straight off warehouse
     * stock, no pick order involved. Flips every ON_STOCK(300) stock unit on [unitLoadId]
     * directly to PACKED(650) (journaled by the underlying state change). Returns the count
     * flipped.
     *
     * **Deliberately a separate method, not [packContainer] plus a source-state flag.**
     * [packContainer]/[unpackContainer] differ only in destination (a boolean is enough because
     * the SOURCE state is fixed by the action being reversed). Here the SOURCE state itself
     * differs (ON_STOCK, not PICKED) because there is no pick step at all, a different action
     * identity, not a parameterization of the same one, so a same-shaped-but-distinct method
     * name reads clearer than overloading [packContainer]'s signature (and every existing
     * `packContainer` call site stays untouched).
     *
     * The caller is expected to have already validated that every stock unit on [unitLoadId] is
     * ON_STOCK (fulfillment's `AdHocShippingUnitService.addAdHocUnit` does this via
     * `StockUnitLookup` before ever calling here), like [packContainer], this method trusts that
     * and simply flips whatever it finds in the matching source state; it does not itself refuse
     * a unit load with no ON_STOCK stock (that case flips zero rows and returns 0).
     */
    fun packAdHocContainer(unitLoadId: Long): Int

    /**
     * Bulk Allocation Sprint C: reverses the DELETABLE tombstoning that DRAINING a batch pick
     * cart leaves behind, so a pre-manifest consolidation-shipment cancel can move the goods
     * back onto the cart they were picked into.
     *
     * Filling a consolidation container physically MOVES stock off the cart (`StockMover.
     * transferToUnitLoad`), unlike the discrete pack path, which only flips a state in place. When
     * the last unit leaves, `StockService.transferStock` marks the emptied stock unit
     * DELETABLE(1000) and `UnitLoadTerminator.trashIfEmpty` marks the now-empty cart unit load
     * DELETABLE too. Both are correct for a container that is finished with; neither is correct
     * for one a cancel is about to refill, and both actively BLOCK the refill --
     * `StockService.transferStock` refuses a DELETABLE target unit load outright, and a merge
     * into a DELETABLE stock row would resurrect a ghost that occupancy reads (`state < DELETABLE`)
     * cannot see.
     *
     * **Targeted, never blanket.** Only the emptied row matching [itemDataId] AND [lotNumber] is
     * revived, because only that row is about to receive stock back. A cart is routinely
     * multi-SKU: flipping every zero-amount DELETABLE row on it would leave PICKED ghosts for
     * items nothing is returning, and those ghosts are permanent -- they sit outside
     * `UnitLoadTerminator`'s gone-predicate (SHIPPED/DELETABLE), so the cart could never be
     * tombstoned again, and outside `StockPurgeService`'s DELETABLE-only reaper, so nothing would
     * ever collect them. [lotNumber] is matched exactly, null included (an unlotted line only
     * ever revives an unlotted row).
     *
     * Restores the unit load to UNDEFINED(0) -- exactly the state `createPickContainer` gives a
     * fresh one -- and the matching EMPTY DELETABLE stock rows to PICKED(600), the canonical state
     * of stock sitting on a pick cart. Touches nothing else: a unit load owned by a different
     * client, and any stock row still carrying a positive amount, are left exactly as they are.
     * Returns how many rows it flipped (the unit load counts as one); 0 means there was nothing
     * to revive, which is a normal outcome, not an error -- the method never throws, and a second
     * call is an idempotent 0.
     *
     * **Fully reverses the trash, bookkeeping included.** The unit-load flip writes a journal row
     * (`JournalService.recordUnitLoadRevived`), an outbox row, and fires
     * [com.karyo.inventory.api.event.UnitLoadRevivedEvent], whose layout observer re-takes the
     * `StorageLocation.allocation` that `UnitLoadTrashedEvent` released -- mirroring
     * `UnitLoadTerminator.fireTrashed` exactly, so a trash/revive round trip leaves occupancy,
     * the journal and the event log all consistent. Each revived stock row is journaled too.
     *
     * **Deliberate forward-only exception**, the same documented one [unpackContainer] takes, for
     * the same caller (`ShippingLifecycleService`'s pre-manifest restore) and by the same means:
     * a direct state write, never `StockService.changeState`, which stays forward-only. Explicit
     * [clientId], not the ambient request context.
     */
    fun reviveDrainedContainer(unitLoadId: Long, itemDataId: Long, lotNumber: String?, clientId: Long): Int
}

package com.karyo.inventory.api.spi

import java.math.BigDecimal

/**
 * In-process stock-to-unit-load move contract — the stock-level sibling of [UnitLoadMover].
 * Implemented by inventory-core and consumed by the tasks module (PT16, putaway-transport
 * sprint Task 3) to fold one unit load's stock into an EXISTING unit load, rather than moving
 * a whole unit load onto a location.
 *
 * Delegates to the existing `StockService.transferStock` — the same mechanism the pick flow
 * (`DefaultStockPicker`, landing picked stock on a new pallet) already exercises, not any REST
 * route — so a move through this SPI carries the same-owner (D1) guard, the aggregate-stocks
 * merge-vs-new-row gate, DELETABLE-on-drain, and journal + outbox writes. The tasks module never
 * touches inventory entities directly.
 */
interface StockMover {

    /**
     * Transfers [amount] of stock unit [stockUnitId] onto [targetUnitLoadId], tagged with
     * [activityCode]. Scoped to the current tenant by the implementation; refuses (409)
     * transferring across goods owners. Returns the TARGET stock unit the amount landed on —
     * an existing same-SKU/lot row when the target unit load's type aggregates stocks,
     * otherwise a newly created row.
     */
    fun transferToUnitLoad(stockUnitId: Long, targetUnitLoadId: Long, amount: BigDecimal, activityCode: String): MovedStock

    /**
     * Explicit-`clientId` variant of [transferToUnitLoad] for trusted in-process SPI callers whose
     * ambient `TenantContext` cannot be relied on to already carry the right tenant -- same
     * doctrine as `StockReserver.reserve(request, clientId)` and the explicit-`clientId` overloads
     * on [StockPicker]: a cross-module port invoked directly (a consolidation pack-out reached
     * from wave-core, a `@Scheduled` sweep) bypasses REST/`TenantFilter`, so the request scope is
     * active but UNPRIMED and the ambient `clientId` defaults to 0. Scoped by [clientId], never
     * the ambient `TenantContext`.
     *
     * **A stock unit not owned by [clientId] throws
     * [com.karyo.inventory.exception.InventoryException.NotFound]**, moving nothing -- the
     * contract inherited unchanged from `StockService.transferStock`'s `findByIdForWrite` call,
     * which reports a row its write scope does not permit as absent rather than as a distinct
     * "forbidden". Everything else -- the same-owner (D1) guard on the TARGET unit load, the
     * aggregate-stocks merge gate, DELETABLE-on-drain, journal + outbox writes -- is identical to
     * the ambient overload AS CODE, with two observable differences.
     *
     * First, journal rows written through this overload carry the ambient principal's username as
     * `operatorName` when a request primed one, and `"system"` otherwise (a `@Scheduled` tick, a
     * port-to-port call on an unprimed request scope) -- the explicit [clientId] fixes the SCOPE
     * only, never the attribution (see `TenantContext.ownerScoped`).
     *
     * Second, and less obvious: the D1 cross-owner guard is effectively UNREACHABLE here. The
     * synthetic context is owner-scoped to [clientId], so a TARGET unit load owned by another
     * client is outside its scope and `unitLoadForWrite` reports it absent --
     * [com.karyo.inventory.exception.InventoryException.NotFound], never `CrossOwner`. D1 still
     * fires for an ambient caller whose real principal can see both owners at once (an OPS
     * principal); it cannot fire for this overload. A caller that needs the 409 `CrossOwner`
     * diagnostic must stay on the ambient overload -- see `ConfirmVariantService.completeAsMerge`
     * (defect-burndown-6 Task 2 ruling), which does exactly that.
     */
    fun transferToUnitLoad(
        stockUnitId: Long,
        targetUnitLoadId: Long,
        amount: BigDecimal,
        activityCode: String,
        clientId: Long,
    ): MovedStock

    /**
     * PT17 (putaway-transport sprint, Task 4): transfers [amount] of stock unit [stockUnitId]
     * onto a unit load AT [locationId], finding-or-creating the target unit load first (so a
     * caller with only a location, not a unit load id — a partial transport-order confirm's
     * location-suggestion branch — never has to resolve one itself):
     *
     * 1. A unit load already at the location whose type aggregates stocks AND that already
     *    carries a same-item/lot/owner stock unit — the amount folds onto it (reuse-summable).
     * 2. Else the first ON_STOCK, unlocked unit load at the location sharing [stockUnitId]'s
     *    own unit load's TYPE — the amount lands there as a new stock row (same-type reuse).
     * 3. Else a brand-new unit load is created at the location, of [stockUnitId]'s own unit
     *    load's type, with a generated label (same idiom as `DefaultStockReceiver`'s receiving
     *    unit-load creation).
     *
     * Once the target is resolved, delegates to the same [transferStock][com.karyo.inventory.service.StockService.transferStock]
     * mechanism [transferToUnitLoad] uses — same-owner (D1) guard, DELETABLE-on-drain,
     * journal + outbox writes, and (409) [com.karyo.inventory.exception.InventoryException.InsufficientStock]
     * when [amount] exceeds the source's available amount.
     */
    fun transferToLocation(
        stockUnitId: Long,
        locationId: Long,
        locationName: String,
        amount: BigDecimal,
        activityCode: String,
    ): MovedStock
}

/** Flat projection of the stock unit [StockMover.transferToUnitLoad] landed the amount on. */
data class MovedStock(val stockUnitId: Long, val unitLoadId: Long, val unitLoadLabel: String)

package com.karyo.inventory.api.spi

import com.karyo.inventory.api.vo.StockState
import java.math.BigDecimal
import java.time.Instant

/**
 * In-process unit-load lookup contract. Implemented by inventory-core and consumed by
 * other modules (the tasks module's putaway flow) instead of a cross-service REST
 * client — same api-only seam as [StockUnitLookup]/[StockReserver].
 *
 * The tasks module needs a unit load's current location, type, weight, and goods owner
 * to (a) seed a PUTAWAY transport order's source and (b) build the location-finder
 * request. This contract hands all of that back without the tasks module depending on
 * inventory entities.
 */
interface UnitLoadLookup {

    /** Returns the unit load's details, or null when no unit load with [unitLoadId] exists. */
    fun findById(unitLoadId: Long): UnitLoadInfo?

    /**
     * Explicit-`clientId` overload of [findById] — an UNSCOPED read followed by a strict
     * `ul.clientId == clientId` equality check, rather than [findById]'s ambient
     * `TenantContext.readScope().permits(...)` (which honors a wider OPS/read scope, not just
     * strict ownership). Task 3 review CRITICAL-1 (replenishment sprint): the sole caller,
     * `TaskService.createReplenishment`, is on `ReplenishmentScheduler`'s `@Scheduled`
     * multi-tenant scan graph, whose thread never primes `TenantContext` — with the ambient
     * overload, `readScope()` resolves to an OWNER(0) scope that `permits(realClientId)` always
     * rejects, so `findById` returns null for a real unit load and `createReplenishment` 400s
     * every scheduled replenishment (`TaskException.InvalidReference`). [command.clientId] is
     * already known and correct at this call site (it is what the resulting order is stamped
     * with), so validating against it directly — not the ambient scope — is both the fix and the
     * more precise check.
     */
    fun findById(unitLoadId: Long, clientId: Long): UnitLoadInfo?

    /**
     * Bulk Allocation Sprint C: label-collision check for minted consolidation containers.
     * `DefaultStockPicker.createPickContainer` fails on a duplicate label (unit-load labels carry
     * a UNIQUE constraint), so the caller's sequence-number retry loop needs a cheap "is this
     * candidate free" predicate. Deliberately a `count` query, not a materializing read: the
     * answer is a yes/no, and nothing about the row itself is wanted.
     *
     * **[clientId] is accepted but not applied.** `unit_loads.label_id` is globally UNIQUE (V102),
     * not unique per tenant, so a tenant-filtered answer would be wrong -- it would call a label
     * free that another tenant already holds, and the insert would fail anyway. The parameter is
     * kept only for uniformity with this SPI's other explicit-tenant reads.
     */
    fun existsByLabel(label: String, clientId: Long): Boolean
}

/**
 * Flat projection of a unit load for cross-module callers.
 *
 * @param weight unit-load weight for the location finder's lifting-capacity filter;
 *        ZERO when the unit load carries no weight (treated as "fits anywhere").
 * @param itemDataId the product on this unit load's single stock unit (INCOMING or ON_STOCK
 *        — final-review F1 fix: INCOMING is included specifically so this resolves correctly
 *        at the moment `TaskService.onGoodsReceiptLineReceived` calls it, before the stock has
 *        been promoted to ON_STOCK at receipt finish), for the location finder's
 *        `LocationFinderRequest.itemDataId` (locations-layout sprint Task 3). `null` when the
 *        unit load carries zero or more-than-one distinct item — a putaway unit load is
 *        expected to be single-SKU, so a mixed load is an honest gap (area FIFO/full-area
 *        hiding is skipped, not guessed at) rather than an arbitrary pick among items.
 * @param strategyDate the FIFO strategy date of that same single stock unit, for
 *        `LocationFinderRequest.strategyDate`. `null` under the same conditions as [itemDataId].
 * @param state the unit load's OWN entity-level state (`StockState` codes; `UNDEFINED` by
 *        default). S5 (outbound-completion sprint): fulfillment's `addAdHocUnit` needs this to
 *        refuse a DELETABLE unit load even when its current stock is live ON_STOCK -- a unit
 *        load's own `state` field is a separate, sometimes-stale flag from its stock units' own
 *        states (see `UnitLoadTerminator.trashIfEmpty`'s KDoc: fresh stock CAN land on an
 *        already-DELETABLE unit load without un-flipping it), so it needs its own explicit read
 *        rather than being inferred from a stock-state scan.
 */
data class UnitLoadInfo(
    val id: Long,
    val label: String,
    val locationId: Long,
    val locationName: String,
    val unitLoadTypeId: Long,
    val weight: BigDecimal,
    val clientId: Long,
    val itemDataId: Long? = null,
    val strategyDate: Instant? = null,
    val state: Int = StockState.UNDEFINED.code,
)

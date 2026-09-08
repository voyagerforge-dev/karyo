package com.karyo.inventory.api.spi

import com.karyo.inventory.api.dto.StockUnitResponse
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * In-process stock unit lookup contract. Implemented by inventory-core and consumed by
 * other modules (e.g. warehouse-layout) instead of a cross-service REST client.
 */
interface StockUnitLookup {
    fun findByItemDataId(itemDataId: Long): List<StockUnitResponse>

    /**
     * Explicit-`clientId` overload of [findByItemDataId] — scoped by strict `clientId` equality
     * (not the ambient-`TenantContext`-`readScope` convention the single-arg overload and every
     * other method on this SPI use). Task 3 review CRITICAL-1 (replenishment sprint): the sole
     * caller, `FixAssignmentService.enrichStockAmount`, is on `ReplenishmentScheduler`'s
     * `@Scheduled` multi-tenant scan graph — the scheduler thread's `TenantContext` request scope
     * IS active but is never primed with a real tenant, so it silently carries its unassigned
     * default (`clientId = 0`, not an exception) and the single-arg overload would silently
     * enrich every fix assignment with client-0's (usually empty) stock, making
     * `FixAssignmentView.currentAmount` read `0` for every real tenant's face regardless of
     * actual on-hand — see [ReplenishmentScheduler]'s KDoc. Same shape as
     * [lotNumbersAtLocation]'s pre-existing deviation for the identical reason.
     */
    fun findByItemDataId(itemDataId: Long, clientId: Long): List<StockUnitResponse>

    /**
     * Batched by-id lookup, full [StockUnitResponse] (incl. `availableAmount`), tenant-scoped
     * like every other method on this SPI. WORKLIST row 20 (extinguish/stock-clearance picks):
     * `ExtinguishService` resolves its caller-supplied `stockUnitIds` this way before reserving
     * each unit's full available amount. An id belonging to another tenant (or unknown/deleted)
     * is simply absent from the result — the caller renders a 404-style refusal from the
     * ABSENCE, never a fabricated row (mirrors [findLocationRefsByIds]/[findContentRefsByIds]).
     */
    fun findByIds(ids: Set<Long>): List<StockUnitResponse>

    /**
     * Explicit-`clientId` overload of [findByIds] -- scoped by strict `clientId` equality (not
     * the ambient-`TenantContext`-`readScope` convention the single-arg overload uses). Wave
     * bulk fulfillment sprint Task 9: `WavePickZoneLookup.zonesByStockUnitIds`'s sole caller is
     * `WavePickService.persistBatchPickOrders`, reachable from `WaveService.release`'s call
     * graph -- and `WaveService.release` is now also reachable from `WaveScheduler`'s
     * `@Scheduled` multi-tenant auto-release loop, whose thread never primes `TenantContext`
     * (the scheduler doctrine documented on `ReplenishmentScheduler`/`CrossDockExpirySweep`).
     * The single-arg overload would silently see the scheduler thread's unassigned default
     * scope (`clientId = 0`), filter out every real tenant's stock units, and degrade every
     * scheduler-driven batch pick to `batchZone = "UNZONED"` regardless of the stock's actual
     * location -- same shape as [findByItemDataId]/[findByUnitLoadId]'s two-arg overloads.
     */
    fun findByIds(ids: Set<Long>, clientId: Long): List<StockUnitResponse>

    /**
     * Every stock unit on [unitLoadId], full [StockUnitResponse], tenant-scoped like [findByIds].
     * WORKLIST row 20: `ExtinguishService`'s `unitLoadId` variant — one Pick per stock unit found
     * here (myWMS `ExtinguishOrderGenerator`: "all stock units of a UnitLoad"). A unit load with
     * no stock, or belonging to another tenant, simply yields an empty list — the caller treats
     * that the same as an unknown id (404-style refusal), never a fabricated unit.
     */
    fun findByUnitLoadId(unitLoadId: Long): List<StockUnitResponse>

    /**
     * Explicit-`clientId` overload of [findByUnitLoadId] -- scoped by strict `clientId` equality
     * (not the ambient-`TenantContext`-`readScope` convention the single-arg overload uses).
     * Task 3 (defect-burndown-4, row 7): the caller, `ConfirmVariantService.denormalizeAtCreation`
     * (via its `singleLiveStockOrNull` helper), is reachable from `TaskService.createReplenishment`
     * on `ReplenishmentScheduler`'s `@Scheduled` multi-tenant scan graph -- the scheduler thread's
     * `TenantContext` request scope IS active but is never primed with a real tenant, so the
     * single-arg overload silently found nothing there (not an exception), leaving a
     * scheduler-minted REPLENISH order's `itemDataId`/`itemDataNumber`/`lotNumber`/`amount`/
     * `sourceStockUnitId` all null. `order.clientId` is already the correct, known-good tenant for
     * this call (the order is stamped with it before the denorm runs). Same shape as
     * [findByItemDataId]'s two-arg overload for the identical reason.
     */
    fun findByUnitLoadId(unitLoadId: Long, clientId: Long): List<StockUnitResponse>

    /**
     * Batch stock-unit id -> source location ref (unit load label + storage location name),
     * tenant-scoped like [findByItemDataId]. Missing ids (deleted/unknown stock unit, or
     * belonging to another tenant) are simply absent from the result map -- callers treat
     * absence as an honest gap (e.g. render "—"), never fabricate a location.
     */
    fun findLocationRefsByIds(ids: Set<Long>): Map<Long, StockLocationRef>

    /**
     * Batch stock-unit id -> best-before/serial content ref, tenant-scoped like
     * [findByItemDataId]. D8: the fulfillment packet content list carries lot on its own
     * `ShippingUnitLine` row but needs best-before/serial via a join back to the stock unit
     * the picked amount actually landed on (`line.sourcePickId` -> `Pick.targetStockUnitId`
     * -> this lookup -- the TARGET, not the source: `ShipmentDocumentService`'s KDoc explains
     * why the box's actual contents track the target, not the batch it was picked from).
     * Missing ids (deleted/unknown stock unit, or a broken join, or belonging to another
     * tenant) are simply absent from the result map -- callers treat absence as an honest
     * gap (render "—"), never fabricate a value.
     */
    fun findContentRefsByIds(ids: Set<Long>): Map<Long, StockContentRef>

    /**
     * Batched per-location occupancy read (locations-layout sprint, Task 3): every
     * physically-present stock row at any of [locationIds], tenant-scoped like the other
     * methods on this SPI. Backs the layout module's putaway area logic (`useAreaStrategyDate`
     * cross-area FIFO hiding and `useItemDataArea` full-area hiding) — the consumer groups
     * rows by [StockOccupancyRef.locationId] and rolls them up per [com.karyo.layout] storage
     * area.
     *
     * **ON_STOCK-window convention** (mirrors [com.karyo.inventory.service.StockService.readAmount]
     * / `StockUnitRepository.sumAmountByItemAndLocation`): state = ON_STOCK(300) *exactly*.
     * INCOMING stock has not landed on a location yet (it is what this very computation is
     * deciding the destination for) and PICKED/PACKED/SHIPPED/DELETABLE has already left — an
     * inclusive `>= PICKED` exclusion would be equivalent for the upper bound, but the
     * established sibling convention filters on the single physically-present state rather
     * than a range, so this method mirrors that exactly instead of introducing a second
     * "excludes terminal states" idiom.
     *
     * **In-flight transport orders are NOT counted** — myWMS's `LocationReserver` includes
     * open transport-order demand in its occupancy math; Karyo does not carry that state onto
     * `StockUnit` at all. The equivalent allocation-in-flight window is already covered by the
     * layout module's own `LocationReservation` soft-reserve (a putaway that has already
     * claimed a destination bumps that location's *effective allocation* directly, independent
     * of this stock-level read) — so the two systems divide the "don't double-book" concern
     * along their own state, without this method needing to reach across into the tasks module.
     */
    fun occupancyByLocationIds(locationIds: Set<Long>): List<StockOccupancyRef>

    /**
     * Batched per-location GROSS WEIGHT read (locations-layout sprint, Task 7 — L4 field/section
     * lifting-capacity group check; since 2026-09-06 also the single-location cap in
     * `LocationService.checkCapacity`). Sums [com.karyo.inventory.domain.model.UnitLoad.weight]
     * for every unit load with at least one stock unit still physically resting at any of
     * [locationIds] - state NOT in SHIPPED(680)/DELETABLE(1000), deliberately WIDER than
     * [occupancyByLocationIds]'s ON_STOCK(300)-exact window because a PACKED or PICKED container
     * still presses down on the rack (`StockUnitRepository.findOnStockUnitLoadWeightByLocationIds`
     * owns that ruling) - and the SAME `weight` field the incoming
     * [com.karyo.layout.spi.LocationFinderRequest.weight] is itself sourced from (see
     * `DefaultUnitLoadLookup`), so the capacity check compares like with like. Summed PER UNIT
     * LOAD, not per stock-unit row — a unit load's `weight` is a whole-load figure set once, so
     * summing every stock-unit row on a mixed-SKU pallet would double-count it.
     *
     * **Deliberately UNSCOPED** by tenant, unlike most reads on this SPI (same shape as
     * [occupantsByLocationIds]): a lifting-capacity cap is a physical constraint on the shared
     * structure, so another goods owner's pallet on the same shelf must count or the load figure
     * silently under-reads. `DefaultStockUnitLookup` owns the full rationale (final-review F3).
     *
     * **Honest degradation:** a unit load that was never weighed (`weight == null`) contributes
     * ZERO to its location's sum — never blocks a capacity check on data this SPI cannot see.
     * Where nothing carries a weight, every location's entry here is (or defaults to) zero,
     * and the capacity check the caller builds on top of this degrades to comparing the cap
     * against only the incoming unit load's own weight.
     *
     * A location id with no physically-present unit load at all is simply absent from the map —
     * callers treat absence as zero occupied weight, the same convention [occupancyByLocationIds]
     * uses via "missing = not tracked/zero", never a fabricated value.
     */
    fun grossWeightByLocationIds(locationIds: Set<Long>): Map<Long, BigDecimal>

    /**
     * Batch stock-unit id -> owning `client_id` (the goods owner, NOT the caller), tenant-scoped
     * like [findByIds] (a wide-readScope OPS principal sees every owner's rows; an OWNER
     * principal only ever sees its own — never a fabricated owner across the scope boundary).
     * M8 fix (final-review): `ExtinguishService` uses this to attribute the EXT order/outbox
     * event to the STOCK's owner rather than `tenantContext.clientId` (the caller), the same
     * attribution-bug class already fixed on the journal/outbox paths. Missing ids (deleted/
     * unknown/out-of-scope stock unit) are simply absent from the result map.
     */
    fun findOwnerClientIdsByIds(ids: Set<Long>): Map<Long, Long>

    /**
     * Distinct non-blank lot numbers on ON_STOCK(300) stock at [locationId], for [clientId] — the
     * same physically-present state window [occupancyByLocationIds] uses. R14 (replenishment
     * sprint Task 3): `ReplenishmentService.scan` reads this for a fix-face's OWN location to
     * build `SourceQuery.faceLotNumbers`, answering whether the face already has a lot committed
     * to it. An empty result means either no stock at the location or none of it is lot-tracked;
     * callers treat both the same way (no lot preference to apply). Public behavioral contract:
     * `docs/functional/replenishment.md#2-source-selection`.
     *
     * **Deviates from this SPI's own convention on purpose:** every other method here reads the
     * ambient `TenantContext` (`readScope`/`clientId`) rather than taking an explicit `clientId`
     * parameter. This method is reachable from `ReplenishmentScheduler`'s `@Scheduled`
     * multi-tenant loop, which deliberately never activates a request scope or primes
     * `TenantContext` (see that class's KDoc) — an explicit parameter, scoped by strict `clientId`
     * equality (not `readScope`'s wider-scope-permits reading), is the only safe shape for that
     * call site.
     */
    fun lotNumbersAtLocation(locationId: Long, clientId: Long): Set<String>

    /**
     * Distinct live occupants per location: every (locationId, clientId, itemDataId) triple with
     * stock physically present (state below DELETABLE, amount > 0) on the given locations.
     *
     * Deliberately UNSCOPED (the ClientLookup.exists precedent): the finder's client-mixing pass
     * must see OTHER owners' occupancy to answer "does a different goods owner hold stock here";
     * a tenant-scoped read is structurally incapable of that answer. What leaks is the bare
     * triple, consumed in-process by the layout finder for a boolean exclusion; never expose
     * through REST. Amounts are deliberately absent: both consumers ask "who/what is here", not
     * "how much".
     */
    fun occupantsByLocationIds(locationIds: Set<Long>): List<LocationOccupantRef>

    /**
     * Batched same-item stock read across [locationIds] (LF10 foundation, location-finder
     * sprint Task 6). Feeds Task 7's `AddToLocationFinder` priority-1 fix-assignment pass: for a
     * set of candidate fix-assignment locations, is every stock row already there the SAME lot as
     * the incoming request. State window: state < DELETABLE(1000) and amount > 0 -- the same
     * physically-present window [occupantsByLocationIds] uses (a PICKED-but-not-yet-shipped row
     * still occupies the face), not the narrower ON_STOCK(300)-exact window
     * [occupancyByLocationIds] uses.
     *
     * Tenant-scoped by explicit [clientId] equality, not the ambient `TenantContext`/`readScope`
     * convention most of this SPI's methods use -- same doctrine as [lotNumbersAtLocation]: a
     * new SPI read in this sprint takes an explicit tenant parameter rather than relying on an
     * ambient request scope that a future scheduler-reachable caller could find unprimed.
     */
    fun itemStocksByLocationIds(itemDataId: Long, locationIds: Set<Long>, clientId: Long): List<ItemStockRef>

    /**
     * FIFO-ordered same-item consolidation candidates: state = ON_STOCK(300),
     * lockType = UNLOCKED(0), for [itemDataId]/[clientId], narrowed to [lotNumber] and/or
     * [bestBefore] only when those are non-null. Public behavioral contract:
     * `docs/functional/location-finder.md#2a-the-add-to-location-search-mode-lf10`. A null filter applies NO
     * predicate on that column; it never means "match a null column value".
     *
     * Ordered strategyDate ASC (nulls last), amount ASC, created ASC, id ASC -- Karyo's canonical
     * FIFO rule (`StockSelectionService` rule 2), one column more specific than the corpus's
     * `strategyDate, amount, created`: `id ASC` is Karyo's own deterministic tie-break, not
     * present in the legacy SQL.
     *
     * [limit] is caller-supplied, not hardcoded here -- Task 7's `AddToLocationFinder` passes its
     * own `FIFO_LIMIT = 200` companion constant as a pragmatic cap on how many FIFO candidates are
     * worth walking before giving up on a search; this method itself has no opinion on what a
     * sane limit is.
     *
     * Tenant-scoped by explicit [clientId] equality, same doctrine as [itemStocksByLocationIds].
     */
    fun fifoConsolidationRefs(
        itemDataId: Long,
        lotNumber: String?,
        bestBefore: LocalDate?,
        clientId: Long,
        limit: Int,
    ): List<ConsolidationStockRef>
}

/** One stock unit's source location, as read by [StockUnitLookup.findLocationRefsByIds]. */
data class StockLocationRef(
    val stockUnitId: Long,
    val unitLoadLabel: String,
    val locationName: String,
)

/**
 * One stock unit's best-before/serial/lot content, as read by [StockUnitLookup.findContentRefsByIds].
 * [lotNumber] was added for WORKLIST row 18 (picked lot/best-before actuals captured at pick
 * confirm) — additive, default `null`, so the existing positional-constructor call sites
 * (D8 packet content list) keep compiling unchanged.
 */
data class StockContentRef(
    val stockUnitId: Long,
    val bestBefore: LocalDate?,
    val serialNumber: String?,
    val lotNumber: String? = null,
)

/**
 * One physically-present stock row at a location, as read by [StockUnitLookup.occupancyByLocationIds].
 * [unitLoadId] backs `plannedStocks` (distinct-unit-load) thresholds; [amount] backs
 * `plannedAmount` (summed-quantity) thresholds; [strategyDate] backs cross-area FIFO ordering.
 */
data class StockOccupancyRef(
    val locationId: Long,
    val itemDataId: Long,
    val unitLoadId: Long?,
    val amount: BigDecimal,
    val strategyDate: Instant?,
)

/**
 * One distinct (location, owner, item) occupant triple, as read by
 * [StockUnitLookup.occupantsByLocationIds]. See that method's KDoc for the deliberately
 * unscoped rationale.
 */
data class LocationOccupantRef(
    val locationId: Long,
    val clientId: Long,
    val itemDataId: Long,
)

/**
 * One same-item stock row at a location, as read by [StockUnitLookup.itemStocksByLocationIds].
 */
data class ItemStockRef(
    val stockUnitId: Long,
    val locationId: Long,
    val lotNumber: String?,
)

/**
 * One FIFO-ordered consolidation candidate, as read by [StockUnitLookup.fifoConsolidationRefs].
 */
data class ConsolidationStockRef(
    val stockUnitId: Long,
    val locationId: Long,
    val locationName: String,
)

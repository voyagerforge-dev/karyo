package com.karyo.layout.spi

import java.time.Instant

/**
 * In-process port for locking/releasing a [com.karyo.layout.domain.model.StorageLocation]
 * for a cycle-count (stocktaking), expanding an area to its location ids, and reading a
 * location name. Implemented by layout-core; originally consumed by the cycle-count module
 * only, but has grown into the general "location facts for other modules" seam (see e.g.
 * [markCounted]/[orderIndexFor], already unrelated to locking) — PT15's [isTransferStaging]
 * joins that pattern for the tasks module rather than minting a narrower SPI.
 */
interface LocationLockPort {
    /** Set the location's lockType to STOCKTAKING (code 7). */
    fun lockForCount(locationId: Long, clientId: Long)

    /** Clear the STOCKTAKING lock — set lockType to UNLOCKED (code 0). */
    fun releaseCount(locationId: Long, clientId: Long)

    /** Return the ids of all [com.karyo.layout.domain.model.StorageLocation]s in the area for the tenant. */
    fun expandAreaToLocations(areaId: Long, clientId: Long): List<Long>

    /**
     * Return the ids of all [com.karyo.layout.domain.model.StorageLocation]s for the tenant
     * whose name matches [pattern] — a SQL `LIKE` pattern, wildcards (`%`/`_`) supplied by the
     * caller and bound as a JPQL parameter (never concatenated). Tenant-scoped like
     * [expandAreaToLocations]. Backs the stocktaking module's location-name-pattern count
     * scope (St2) — parity with legacy myWMS's pattern filter.
     */
    fun findIdsByNamePattern(pattern: String, clientId: Long): List<Long>

    /** Return the location's name, or null if it doesn't exist or belongs to a different client. */
    fun locationName(locationId: Long, clientId: Long): String?

    /**
     * Return the ids of EVERY [com.karyo.layout.domain.model.StorageLocation] owned by
     * [clientId] — empty locations included — ordered by `orderIndex` (NULLS LAST) then `name`,
     * the same walking order the stocktaking module's travel-path dispatch uses, so a full
     * inventory is generated in aisle order rather than insertion order.
     *
     * Backs the stocktaking module's END_OF_PERIOD full-inventory scope
     * (`FullWarehouseScope`, St5). Tenant-scoped by strict `clientId` equality, exactly like
     * [expandAreaToLocations]/[findIdsByNamePattern] — a shared (`client_id = 0`) location is
     * NOT counted by a tenant's full inventory, because no tenant-facing layout read can reach
     * one either (`LocationService.findEntityById` demands the same strict match).
     */
    fun allStorageLocationIds(clientId: Long): List<Long>

    /**
     * Return the subset of [locationIds] that currently carry a NON-ZERO
     * [com.karyo.layout.vo.LockType] — i.e. the locations a caller must not touch. One batched
     * query, never per-location.
     *
     * Deliberately a boolean *filter*, not a lock-code map: the stocktaking caller has no
     * business interpreting a location lock's reason (QUARANTINE vs DAMAGE vs an in-flight
     * count), only whether the location is available. Callers that need the code already read
     * the location itself.
     *
     * Backs the END_OF_PERIOD skip-list (St5). Snapshot semantics: the caller reads this ONCE
     * before it starts locking, so locations it locks itself are not mistaken for pre-existing
     * locks.
     */
    fun lockedLocationIds(locationIds: List<Long>, clientId: Long): Set<Long>

    /**
     * Row 4 (defect-burndown-4, Task 5): the subset of [locationIds] whose EFFECTIVE allocation
     * -- base [com.karyo.layout.domain.model.StorageLocation.allocation] PLUS any live
     * [com.karyo.layout.domain.model.LocationReservation] load, the same "effective allocation"
     * doctrine [com.karyo.layout.service.LocationFinderService] already applies to putaway
     * candidates -- is `>= 100`: full, or already soft-reserved for another in-flight move, and
     * so must not be handed out as a NEW destination. One batched query per input collection,
     * never per-location. Sibling to [lockedLocationIds] -- lock and occupancy are independent
     * gates (a location can be occupied without being locked, or vice versa), so a caller that
     * cares about both reads both. Backs [com.karyo.replenishment.service.
     * AreaReplenishmentService.chooseDestination]'s occupancy-aware destination filter.
     */
    fun occupiedLocationIds(locationIds: List<Long>, clientId: Long): Set<Long>

    /**
     * L3 (locations-layout sprint, Task 6): stamp `lastCountedAt = at` on every location in
     * [locationIds] — the real writer that replaces the demo-seeder-only column. Called by
     * `StocktakingService.finishOrder` for the order's counted location(s), at cycle-count
     * accept/finish time (both the discrepancy-accept path and the no-discrepancy auto-finish
     * path share that one call site). **Unscoped** — same trust model as the layout-core
     * `LocationService.updateAllocation`/`checkCapacity` internal writers: the caller is
     * trusted in-process code, not an external REST caller.
     *
     * **Deliberately NOT called on pick.** myWMS also stamps `stockTakingDate` when stock is
     * picked from a location; that pick-time stamp is out of scope for this task (WORKLIST
     * follow-up) — a location that is only ever picked from, never cycle-counted, keeps
     * `lastCountedAt == null`, which is an honest gap, not a bug.
     */
    fun markCounted(locationIds: List<Long>, at: Instant)

    /**
     * Batched `id -> orderIndex` for [locationIds] — **unscoped, like [markCounted]**: the
     * caller already knows the ids belong to it (it just resolved them from a tenant-scoped
     * count scope or work-item read), so there is no `clientId` to check against.
     *
     * An id [locationIds] doesn't resolve to (deleted, or never existed) is simply absent from
     * the returned map — never a `0`/sentinel entry. That absence IS the "NULLS LAST" half of
     * the `orderIndex NULLS LAST, name` walking-order contract: a caller sorting by this map's
     * values (`map[id]`, a nullable lookup) puts unresolvable ids after every id the map *can*
     * answer for, exactly mirroring [StorageLocationRepository.findAllIdsOrdered]'s
     * `order by orderIndex asc nulls last` SQL clause.
     *
     * Backs the stocktaking module's generation-time walking-order sort
     * (`StocktakingService.startCount`, St6) and [CountWorkProvider]'s `WorkItem.travelOrder`
     * population for the opt-in `TRAVEL_PATH` dispatch strategy — same underlying `orderIndex`
     * column, two different consumers (sort locations before generating orders vs. carry the
     * value onto the generated work item for a later dispatch-time sort).
     */
    fun orderIndexFor(locationIds: List<Long>): Map<Long, Int>

    /**
     * PT15: true when [locationId] belongs to (via its [com.karyo.layout.domain.model.StorageLocation.locationCluster])
     * a [com.karyo.layout.domain.model.StorageArea] flagged `transferStaging` — a waypoint, not
     * a final destination. Drives [com.karyo.tasks.service.ChainContinuationService]'s decision
     * to spin up a TRANSFER successor when a transport order completes there. A location with no
     * cluster, or whose cluster belongs to no such area, is `false` — the common case, and not an
     * error (most locations are ordinary final destinations, staging or not is opt-in per area).
     */
    fun isTransferStaging(locationId: Long): Boolean

    /**
     * Row 16 (defect-burndown-4, Task 11): the subset of [locationIds] that currently carry a
     * live (non-expired) [com.karyo.layout.domain.model.LocationReservation], i.e. an
     * inbound putaway has already soft-claimed the location and stock is about to land there.
     * One batched query, never per-location, mirroring [occupiedLocationIds]/[lockedLocationIds].
     *
     * **Ruling:** an inbound-reserved location is NOT countable. A blind count reads the
     * location's current stock; work already in flight toward it (a putaway that has claimed
     * but not yet placed) would either race the count or make its snapshot stale the moment the
     * putaway lands, so [StocktakingService.startCount]'s END_OF_PERIOD generation treats a
     * reserved location the same as an already-locked one: skipped, not counted, reported in
     * `skippedLocations`.
     *
     * **Unscoped, like [markCounted]/[orderIndexFor]:** [com.karyo.layout.domain.model.LocationReservation]
     * carries no client column (it's an internal transport-order-keyed mechanism, not a
     * tenant-owned aggregate, see its own KDoc), so there is no `clientId` to filter by here.
     * The caller already resolved [locationIds] from a tenant-scoped count scope.
     */
    fun reservedLocationIds(locationIds: List<Long>): Set<Long>
}

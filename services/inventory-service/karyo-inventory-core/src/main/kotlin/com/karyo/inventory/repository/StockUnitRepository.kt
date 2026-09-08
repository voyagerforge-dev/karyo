package com.karyo.inventory.repository

import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@ApplicationScoped
class StockUnitRepository : PanacheRepository<StockUnit> {

    fun findByClientId(clientId: Long): List<StockUnit> =
        list("clientId", clientId)

    fun findByItemDataId(itemDataId: Long, clientId: Long): List<StockUnit> =
        list("itemDataId = ?1 and clientId = ?2", itemDataId, clientId)

    fun findByUnitLoadId(unitLoadId: Long): List<StockUnit> =
        list("unitLoad.id", unitLoadId)

    /** Explicit-`clientId` overload of [findByUnitLoadId] -- same shape as [findByItemDataId]. */
    fun findByUnitLoadId(unitLoadId: Long, clientId: Long): List<StockUnit> =
        list("unitLoad.id = ?1 and clientId = ?2", unitLoadId, clientId)

    /**
     * Plural sibling of [findByUnitLoadId] -- backs [UnitLoadWeightCalculator.recalculateAll]
     * (:1411, defect-burndown-5): ONE query for every unit load's stock, rather than looping
     * [findByUnitLoadId] once per unit load (the N+1 the batch overload exists to avoid).
     * Unfiltered by tenant, matching [findByIds] -- callers apply scope.
     */
    fun findByUnitLoadIds(unitLoadIds: Collection<Long>): List<StockUnit> =
        if (unitLoadIds.isEmpty()) emptyList() else list("unitLoad.id in ?1", unitLoadIds)

    /** Batched by-id lookup (e.g. for [com.karyo.inventory.api.spi.StockUnitLookup]); unfiltered — callers apply tenant scope. */
    fun findByIds(ids: Collection<Long>): List<StockUnit> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    /**
     * Core stock selection query. Filters by item, client, state=ON_STOCK (300), lock,
     * and optional lot. Orders by FIFO: strategyDate ASC, amount ASC, created ASC, id ASC.
     */
    fun findForSelection(
        itemDataId: Long,
        clientId: Long,
        includeLocked: Boolean = false,
        lotNumber: String? = null,
    ): List<StockUnit> {
        val query = StringBuilder(
            "itemDataId = ?1 and clientId = ?2 and state = 300 and amount > reservedAmount"
        )
        val params = mutableListOf<Any>(itemDataId, clientId)
        var idx = 3

        if (!includeLocked) {
            // D3/F1 (user decision 2026-07-25, myWMS-faithful — PickingStockFinder filters
            // unitLoad.lock=0): the pallet lock is a READ-TIME INVARIANT, not a write-time
            // snapshot. However stock lands on a locked pallet (late receive, transfer,
            // carrier nesting), it is unpickable until the pallet is unlocked.
            query.append(" and lockType = 0 and unitLoad.lockType = 0")
        }
        lotNumber?.let {
            query.append(" and lotNumber = ?$idx")
            params.add(it); idx++
        }

        query.append(" order by strategyDate asc, amount asc, created asc, id asc")

        return list(query.toString(), *params.toTypedArray())
    }

    /**
     * Σ on-hand amount for one item at one location. [clientId] null means unscoped (an ops
     * principal); a value scopes the sum to that goods owner. The scope predicate must be in the
     * query — an aggregate cannot be filtered in memory after the fact.
     */
    fun sumAmountByItemAndLocation(itemDataId: Long, locationId: Long, clientId: Long?): BigDecimal {
        val base = "select coalesce(sum(su.amount), 0) from StockUnit su " +
            "where su.itemDataId = :itemDataId and su.unitLoad.storageLocationId = :locationId " +
            "and su.state = :state"
        val jpql = if (clientId == null) base else "$base and su.clientId = :clientId"

        val query = getEntityManager().createQuery(jpql, BigDecimal::class.java)
            .setParameter("itemDataId", itemDataId)
            .setParameter("locationId", locationId)
            .setParameter("state", StockState.ON_STOCK.code)
        if (clientId != null) query.setParameter("clientId", clientId)

        return query.singleResult ?: BigDecimal.ZERO
    }

    /**
     * Raw tuple projection (locationId, itemDataId, unitLoadId, amount, strategyDate, clientId)
     * for [com.karyo.inventory.api.spi.StockUnitLookup.occupancyByLocationIds] — mirrors
     * [StorageAreaRepository.clusterPairsForAreas]'s tuple-select style in the layout module to
     * avoid an N+1 walk over each row's lazy `unitLoad` association. `clientId` rides along so
     * the caller can apply tenant scope in memory, the same pattern [findByIds]'s callers use.
     * State = ON_STOCK(300) exactly, mirroring [sumAmountByItemAndLocation]'s "physically
     * present" convention. Unfiltered by tenant — callers apply scope.
     */
    @Suppress("UNCHECKED_CAST")
    fun findOnStockOccupancyByLocationIds(locationIds: Collection<Long>): List<Array<Any?>> {
        if (locationIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery(
                "select su.unitLoad.storageLocationId, su.itemDataId, su.unitLoad.id, su.amount, su.strategyDate, su.clientId " +
                    "from StockUnit su where su.state = :state and su.unitLoad.storageLocationId in :locationIds"
            )
            .setParameter("state", StockState.ON_STOCK.code)
            .setParameter("locationIds", locationIds)
            .resultList as List<Array<Any?>>
    }

    /**
     * `(sum(amount), count(*))` of settled ON_STOCK(300)/unlocked/unreserved stock for
     * [itemDataId]/[clientId] across [locationIds] — backs
     * [com.karyo.inventory.api.spi.StockSummaryLookup.summaryInLocations] (R12a, replenishment
     * sprint Task 5). See that method's KDoc for the exact filter and why `reservedAmount = 0`
     * is a Karyo planning-strictness addition beyond the behavioral corpus. ONE aggregate
     * query — `sum` is `coalesce`d to `ZERO` so an empty result set never yields SQL `null`.
     * Caller guards the empty-[locationIds] case (an empty `IN ()` is invalid SQL on some
     * dialects); this method does not re-guard it.
     */
    fun summaryInLocations(itemDataId: Long, clientId: Long, locationIds: Collection<Long>): Pair<BigDecimal, Long> {
        val row = getEntityManager()
            .createQuery(
                "select coalesce(sum(su.amount), 0), count(su) from StockUnit su " +
                    "where su.itemDataId = :itemDataId and su.clientId = :clientId " +
                    "and su.state = :state and su.lockType = 0 and su.reservedAmount = 0 " +
                    "and su.unitLoad.storageLocationId in :locationIds"
            )
            .setParameter("itemDataId", itemDataId)
            .setParameter("clientId", clientId)
            .setParameter("state", StockState.ON_STOCK.code)
            .setParameter("locationIds", locationIds)
            .singleResult as Array<*>
        return (row[0] as BigDecimal) to (row[1] as Long)
    }

    /**
     * Distinct non-blank lot numbers on ON_STOCK(300) stock at [locationId], scoped by strict
     * [clientId] equality — backs
     * [com.karyo.inventory.api.spi.StockUnitLookup.lotNumbersAtLocation] (R14, replenishment
     * sprint Task 3). See that method's KDoc for why this is a strict-equality scope rather than
     * the ambient-`TenantContext`-`readScope` convention most of this repository's SPI-backing
     * queries use.
     */
    fun findLotNumbersByLocation(locationId: Long, clientId: Long): List<String> =
        getEntityManager()
            .createQuery(
                "select distinct su.lotNumber from StockUnit su " +
                    "where su.unitLoad.storageLocationId = :locationId and su.clientId = :clientId " +
                    "and su.state = :state and su.lotNumber is not null",
                String::class.java,
            )
            .setParameter("locationId", locationId)
            .setParameter("clientId", clientId)
            .setParameter("state", StockState.ON_STOCK.code)
            .resultList

    /**
     * Raw tuple projection (locationId, clientId, itemDataId) for
     * [com.karyo.inventory.api.spi.StockUnitLookup.occupantsByLocationIds] (location-finder
     * sprint Task 4). `DISTINCT` over the triple, not per stock-unit row -- two lots of the same
     * item for the same owner on one location must collapse to a single occupant. State window
     * is state < DELETABLE(1000), wider than [findOnStockOccupancyByLocationIds]'s exact
     * ON_STOCK(300): occupancy for the finder's client-mixing check means "still physically here
     * in any pre-terminal state" (e.g. PICKED stock not yet shipped still occupies the slot),
     * not the narrower "settled and available" window that read's callers need. `amount > 0`
     * excludes stock rows drained to zero but not yet purged. Unfiltered by tenant -- the caller
     * is deliberately unscoped, see that method's KDoc.
     */
    @Suppress("UNCHECKED_CAST")
    fun findOccupantsByLocationIds(locationIds: Collection<Long>): List<Array<Any?>> {
        if (locationIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery(
                "select distinct su.unitLoad.storageLocationId, su.clientId, su.itemDataId " +
                    "from StockUnit su where su.unitLoad.storageLocationId in :locationIds " +
                    "and su.state < :deletable and su.amount > 0"
            )
            .setParameter("locationIds", locationIds)
            .setParameter("deletable", StockState.DELETABLE.code)
            .resultList as List<Array<Any?>>
    }

    /**
     * Raw tuple projection (stockUnitId, locationId, lotNumber) for
     * [com.karyo.inventory.api.spi.StockUnitLookup.itemStocksByLocationIds] (location-finder
     * sprint Task 6, LF10 foundation). Same physically-present state window as
     * [findOccupantsByLocationIds]: state < DELETABLE(1000), amount > 0. Scoped by strict
     * [clientId] equality (not [readScope]) -- same doctrine as [findLotNumbersByLocation].
     */
    @Suppress("UNCHECKED_CAST")
    fun findItemStocksByLocationIds(itemDataId: Long, locationIds: Collection<Long>, clientId: Long): List<Array<Any?>> {
        if (locationIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery(
                "select su.id, su.unitLoad.storageLocationId, su.lotNumber from StockUnit su " +
                    "where su.itemDataId = :itemDataId and su.unitLoad.storageLocationId in :locationIds " +
                    "and su.clientId = :clientId and su.state < :deletable and su.amount > 0"
            )
            .setParameter("itemDataId", itemDataId)
            .setParameter("locationIds", locationIds)
            .setParameter("clientId", clientId)
            .setParameter("deletable", StockState.DELETABLE.code)
            .resultList as List<Array<Any?>>
    }

    /**
     * FIFO-ordered raw tuple projection (stockUnitId, locationId, locationName) for
     * [com.karyo.inventory.api.spi.StockUnitLookup.fifoConsolidationRefs] (location-finder
     * sprint Task 6, LF10 foundation). state = ON_STOCK(300), lockType = UNLOCKED(0), scoped by
     * strict [clientId] equality. [lotNumber]/[bestBefore] each add a predicate only when
     * non-null (StringBuilder conditional-clause style, mirrors
     * [com.karyo.layout.repository.StorageLocationRepository.findPutawayCandidates] on the
     * layout side of this seam). Ordered strategyDate ASC NULLS LAST, amount ASC, created ASC,
     * id ASC; capped at [limit] via `setMaxResults`.
     */
    @Suppress("UNCHECKED_CAST")
    fun findFifoConsolidationCandidates(
        itemDataId: Long,
        lotNumber: String?,
        bestBefore: LocalDate?,
        clientId: Long,
        limit: Int,
    ): List<Array<Any?>> {
        val sb = StringBuilder(
            "select su.id, su.unitLoad.storageLocationId, su.unitLoad.storageLocationName from StockUnit su " +
                "where su.itemDataId = :itemDataId and su.clientId = :clientId " +
                "and su.state = :state and su.lockType = :unlocked"
        )
        val params = HashMap<String, Any>()
        params["itemDataId"] = itemDataId
        params["clientId"] = clientId
        params["state"] = StockState.ON_STOCK.code
        params["unlocked"] = LockType.UNLOCKED.code
        if (lotNumber != null) {
            sb.append(" and su.lotNumber = :lotNumber")
            params["lotNumber"] = lotNumber
        }
        if (bestBefore != null) {
            sb.append(" and su.bestBefore = :bestBefore")
            params["bestBefore"] = bestBefore
        }
        sb.append(" order by su.strategyDate asc nulls last, su.amount asc, su.created asc, su.id asc")
        return getEntityManager()
            .createQuery(sb.toString())
            .setMaxResults(limit)
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .resultList as List<Array<Any?>>
    }

    /**
     * Raw tuple projection (locationId, unitLoadId, weight, clientId) for
     * [com.karyo.inventory.api.spi.StockUnitLookup.grossWeightByLocationIds] (locations-layout
     * sprint Task 7). `DISTINCT` per (locationId, unitLoadId) pair — not per stock-unit row —
     * since [com.karyo.inventory.domain.model.UnitLoad.weight] is a whole-load figure shared by
     * every stock unit on that load; multiple stock-unit rows for the same unit load would
     * otherwise double-count it in the caller's sum. Unfiltered by tenant -- callers apply
     * scope, same pattern as the sibling method.
     *
     * **State window widened row :1436 (defect-burndown-5, A5 ruling):** was ON_STOCK(300)
     * exactly; now `state not in (SHIPPED(680), DELETABLE(1000))` -- the same two codes as
     * [UnitLoadTerminator.GONE_STATES] (hardcoded here rather than imported, since this SPI-
     * backing query in inventory-core must not reach into that class's internal companion, and
     * a JPQL `in (:list)` parameter can't reference a Kotlin `internal` constant across that
     * boundary anyway). Why: the sole consumer, [com.karyo.inventory.api.spi.StockUnitLookup.
     * grossWeightByLocationIds], is a physical lifting-capacity safety cap on the layout side
     * (`GroupCapacityReader`'s field/section groups, and since 2026-09-06 the single-location
     * `LocationService.checkCapacity`) -- a PACKED or PICKED container still physically
     * rests on the rack and must count, unlike the narrower "settled and available" window
     * [findOnStockOccupancyByLocationIds] answers for a different question (selectable stock).
     * The write side, [UnitLoadWeightCalculator], already uses exactly this window (via
     * [UnitLoadTerminator.GONE_STATES]), so reader and writer now agree. Note the interaction
     * with row-M5 ship-time promotion (Task 1, defect-burndown-5): SHIPPED is transient once a
     * ship promotes stock straight to DELETABLE in the same transaction, so in practice this
     * window reads as "everything not yet promoted at ship".
     */
    @Suppress("UNCHECKED_CAST")
    fun findOnStockUnitLoadWeightByLocationIds(locationIds: Collection<Long>): List<Array<Any?>> {
        if (locationIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery(
                "select distinct su.unitLoad.storageLocationId, su.unitLoad.id, su.unitLoad.weight, su.unitLoad.clientId " +
                    "from StockUnit su where su.state not in (:shipped, :deletable) " +
                    "and su.unitLoad.storageLocationId in :locationIds"
            )
            .setParameter("shipped", StockState.SHIPPED.code)
            .setParameter("deletable", StockState.DELETABLE.code)
            .setParameter("locationIds", locationIds)
            .resultList as List<Array<Any?>>
    }

    /**
     * Row 18: distinct client ids with at least one DELETABLE(1000) stock unit -- the tenant
     * loop [com.karyo.inventory.service.StockPurgeScheduler.runOnce] sweeps. Deliberately
     * unscoped, same native-query shape as `TenantEnumerator.activeClientIds`/
     * `FixAssignmentRepository.distinctClientIds`.
     */
    fun clientIdsWithDeletableStock(): List<Long> =
        getEntityManager()
            .createNativeQuery("SELECT DISTINCT client_id FROM karyo.stock_units WHERE state = :state")
            .setParameter("state", StockState.DELETABLE.code)
            .resultList
            .map { (it as Number).toLong() }

    /**
     * Row 18 purge candidates: DELETABLE(1000) stock units for [clientId] whose [StockUnit.modified]
     * stamp is older than [cutoff] (the SC16 retention window). Entities, not ids: the caller
     * (`StockPurgeService`) reads `unitLoad.labelId`/`unitLoad.storageLocationName` for the
     * journal row it writes before each delete.
     */
    /**
     * Final-review wave, IMPORTANT 1: `reservedAmount` is a scalar claim on the row itself, with
     * no `order_line_reservations`/`Pick` row for [PurgeBlockerLookup] to see (the reserve-a-
     * DELETABLE-row gap is `StockService.reserveStock`'s -- it guards `lockType` and
     * `availableAmount` but not `state`). A DELETABLE row an integrator still holds a live
     * reservation on must not be purged just because no lookup-based blocker fired for it, so the
     * predicate excludes it directly here rather than adding yet another [PurgeBlockerLookup].
     *
     * Row 18 fix round 2 (defect-burndown-5): `join fetch su.unitLoad` (N+1, register row filed
     * "reaper lazily loads each candidate's unit load label per row") -- `StockPurgeService`
     * reads `su.unitLoad.labelId`/`storageLocationName` for every candidate's journal row, and
     * without the fetch that is one extra SELECT per row instead of one JOIN for the whole
     * batch. `setMaxResults` alongside a to-one join fetch is safe (no result-multiplying
     * to-many join in this query), so capping and eager-fetching compose cleanly. [limit] is
     * [StockPurgeService]'s `karyo.inventory.purge.batch-size` (I3) -- bounds one call's work so
     * a tick is monotonic progress, not one unbounded all-or-nothing transaction.
     *
     * **Row 1 fix (defect-tail-2, 2026-08-17):** `order by su.modified asc, su.id asc` -- oldest
     * first, `id` as a deterministic tiebreaker for rows sharing a `modified` instant. Without an
     * `ORDER BY`, the batch cap above selects an ARBITRARY `limit`-sized slice of this client's
     * DELETABLE backlog every tick (whatever order the database happens to return), so a tenant
     * whose permanently-blocked rows outnumber the batch size could have those same rows re-fill
     * the window on every call, starving the reaper for any older, genuinely purgeable row sitting
     * behind them. Deliberately NOT excluding blocked rows in this SQL: the blockers
     * ([com.karyo.inventory.api.spi.PurgeBlockerLookup]) are cross-module lookups, not queryable
     * predicates this query could join against. With the netting fix on the orders-side blocker
     * (see [com.karyo.orders.service.OrderPurgeBlockerLookup]'s KDoc), a PERMANENT block becomes
     * rare -- most blocks are transient (an open pick, a live reservation) and clear on a later
     * tick regardless of ordering. `ORDER BY` alone is what actually fixes starvation here: it
     * makes the candidate window advance monotonically oldest-first, so even a genuinely permanent
     * block (should one ever exist from a different blocker) only ever holds up rows strictly
     * younger than itself, never rows older than it that already cleared the window on a prior
     * tick.
     */
    fun findPurgeCandidates(clientId: Long, cutoff: Instant, limit: Int): List<StockUnit> =
        getEntityManager()
            .createQuery(
                "select su from StockUnit su join fetch su.unitLoad " +
                    "where su.clientId = :clientId and su.state = :state and su.modified < :cutoff " +
                    "and su.reservedAmount <= 0 " +
                    "order by su.modified asc, su.id asc",
                StockUnit::class.java,
            )
            .setParameter("clientId", clientId)
            .setParameter("state", StockState.DELETABLE.code)
            .setParameter("cutoff", cutoff)
            .setMaxResults(limit)
            .resultList
}

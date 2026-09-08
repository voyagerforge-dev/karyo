package com.karyo.layout.repository

import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.vo.AreaUsage
import io.quarkus.hibernate.orm.panache.kotlin.PanacheQuery
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import io.quarkus.panache.common.Parameters
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StorageLocationRepository : PanacheRepository<StorageLocation> {
    fun findByName(name: String, clientId: Long): StorageLocation? =
        find("name = ?1 and clientId = ?2", name, clientId).firstResult()

    fun findByScanCode(scanCode: String, clientId: Long): StorageLocation? =
        find("scanCode = ?1 and clientId = ?2", scanCode, clientId).firstResult()

    fun findByArea(areaId: Long, clientId: Long): List<StorageLocation> =
        list("area.id = ?1 and clientId = ?2", areaId, clientId)

    /**
     * Single-location lookup by [id], scoped to [clientId] by strict equality (no `client_id = 0`
     * shared fallback) -- backs [com.karyo.layout.spi.StorageLocationLookup.findById] (Row 10,
     * stock-and-orders sprint Task 7). Mirrors [findPickingLocationIds]'s tenant-owned-config
     * scoping convention.
     */
    fun findByIdAndClientId(id: Long, clientId: Long): StorageLocation? =
        find("id = ?1 and clientId = ?2", id, clientId).firstResult()

    /**
     * Batched `id -> name` projection for [ids], scoped to [clientId] by strict equality -- backs
     * [com.karyo.layout.spi.StorageLocationLookup.findNamesByIds] (stock-and-orders sprint,
     * Task 7 fix round 1), so a page of delivery orders resolves destination names in ONE query
     * instead of one per order. An id not found (or belonging to a different client) is simply
     * absent, same "no synthesized entry" contract as [findAllocationByIds].
     */
    @Suppress("UNCHECKED_CAST")
    fun findNamesByIds(ids: Collection<Long>, clientId: Long): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return (
            getEntityManager()
                .createQuery(
                    "select l.id, l.name from StorageLocation l where l.id in :ids and l.clientId = :clientId",
                )
                .setParameter("ids", ids)
                .setParameter("clientId", clientId)
                .resultList as List<Array<Any?>>
            )
            .mapNotNull { row ->
                val id = row[0] as? Number ?: return@mapNotNull null
                val name = row[1] as? String ?: return@mapNotNull null
                id.toLong() to name
            }
            .toMap()
    }

    /**
     * Id-only projection of locations whose `name` matches [pattern] (SQL `LIKE`, caller
     * supplies `%`/`_` wildcards) for [clientId]. Backs
     * [com.karyo.layout.spi.LocationLockPort.findIdsByNamePattern] (St2) — the pattern is
     * always bound as a JPQL parameter, never concatenated into the query string.
     */
    fun findIdsByNamePattern(pattern: String, clientId: Long): List<Long> =
        getEntityManager()
            .createQuery(
                "select l.id from StorageLocation l where l.name like ?1 and l.clientId = ?2",
                Long::class.java,
            )
            .setParameter(1, pattern)
            .setParameter(2, clientId)
            .resultList

    /**
     * Id-only projection of EVERY location owned by [clientId], ordered `orderIndex` (nulls
     * last) then `name` — backs [com.karyo.layout.spi.LocationLockPort.allStorageLocationIds]
     * (St5, full inventory). `nulls last` is belt-and-braces: `order_index` is NOT NULL today,
     * so the clause is a no-op, but it keeps the ordering contract honest if the column ever
     * goes nullable.
     */
    fun findAllIdsOrdered(clientId: Long): List<Long> =
        getEntityManager()
            .createQuery(
                "select l.id from StorageLocation l where l.clientId = ?1 " +
                    "order by l.orderIndex asc nulls last, l.name asc",
                Long::class.java,
            )
            .setParameter(1, clientId)
            .resultList

    /**
     * Ids among [locationIds] whose `lockType` is non-zero (any lock), for [clientId] — backs
     * [com.karyo.layout.spi.LocationLockPort.lockedLocationIds] (St5). One batched query; an
     * empty input short-circuits (an empty `in ()` is invalid SQL on some dialects).
     */
    fun findLockedIds(locationIds: List<Long>, clientId: Long): List<Long> {
        if (locationIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery(
                "select l.id from StorageLocation l " +
                    "where l.id in :ids and l.clientId = :clientId and l.lockType <> 0",
                Long::class.java,
            )
            .setParameter("ids", locationIds)
            .setParameter("clientId", clientId)
            .resultList
    }

    /**
     * Batched `id -> allocation` (base allocation only, no reservation load) for [locationIds],
     * scoped to [clientId] -- backs [com.karyo.layout.spi.LocationLockPort.occupiedLocationIds]
     * (Row 4, defect-burndown-4 Task 5), which folds in live reservation load separately (same
     * "base query, then in-service" split [com.karyo.layout.service.LocationFinderService]
     * already uses). An id not found (or belonging to a different client) is simply absent, same
     * "no synthesized entry" contract as [findOrderIndexByIds].
     */
    @Suppress("UNCHECKED_CAST")
    fun findAllocationByIds(locationIds: List<Long>, clientId: Long): Map<Long, java.math.BigDecimal> {
        if (locationIds.isEmpty()) return emptyMap()
        return (
            getEntityManager()
                .createQuery(
                    "select l.id, l.allocation from StorageLocation l where l.id in :ids and l.clientId = :clientId",
                )
                .setParameter("ids", locationIds)
                .setParameter("clientId", clientId)
                .resultList as List<Array<Any?>>
            )
            .mapNotNull { row ->
                val id = row[0] as? Number ?: return@mapNotNull null
                val allocation = row[1] as? java.math.BigDecimal ?: return@mapNotNull null
                id.toLong() to allocation
            }
            .toMap()
    }

    /**
     * Raw `(id, orderIndex)` tuples for [locationIds] — backs
     * [com.karyo.layout.spi.LocationLockPort.orderIndexFor] (St6, stocktaking-block sprint).
     * Unscoped (no `clientId`), mirroring [findLockedIds]'s empty-input short-circuit; ids not
     * found are simply absent from the result, never a synthesized `0` row.
     */
    @Suppress("UNCHECKED_CAST")
    fun findOrderIndexByIds(locationIds: List<Long>): List<Array<Any?>> {
        if (locationIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery("select l.id, l.orderIndex from StorageLocation l where l.id in :ids")
            .setParameter("ids", locationIds)
            .resultList as List<Array<Any?>>
    }

    fun findByZone(zoneId: Long, clientId: Long): List<StorageLocation> =
        list("zone.id = ?1 and clientId = ?2", zoneId, clientId)

    /**
     * Batched `id -> zone name` projection for [locationIds], scoped to [clientId] by strict
     * equality -- backs [com.karyo.layout.spi.StorageLocationLookup.zoneNamesByLocationIds]
     * (wave bulk fulfillment sprint, Task 8). A `left join` so an unzoned location still
     * contributes an `(id, null)` row rather than being dropped from the result entirely --
     * mirrors [findAllocationByIds]'s tuple-select style. An id not found (or belonging to a
     * different client) is simply absent, same "no synthesized entry" contract as
     * [findNamesByIds].
     */
    @Suppress("UNCHECKED_CAST")
    fun findZoneNamesByIds(locationIds: Collection<Long>, clientId: Long): Map<Long, String?> {
        if (locationIds.isEmpty()) return emptyMap()
        return (
            getEntityManager()
                .createQuery(
                    "select l.id, z.name from StorageLocation l left join l.zone z " +
                        "where l.id in :ids and l.clientId = :clientId",
                )
                .setParameter("ids", locationIds)
                .setParameter("clientId", clientId)
                .resultList as List<Array<Any?>>
            )
            .mapNotNull { row ->
                val id = row[0] as? Number ?: return@mapNotNull null
                id.toLong() to (row[1] as String?)
            }
            .toMap()
    }

    fun findByClientId(clientId: Long): List<StorageLocation> =
        list("clientId", clientId)

    fun findFirstByAreaIdsAndClient(areaIds: List<Long>, clientId: Long): StorageLocation? =
        if (areaIds.isEmpty()) null
        else find("area.id in ?1 and clientId = ?2", areaIds, clientId).firstResult()

    fun countByAreaId(areaId: Long): Long = count("area.id", areaId)

    fun countByZoneId(zoneId: Long): Long = count("zone.id", zoneId)

    fun countByLocationTypeId(locationTypeId: Long): Long = count("locationType.id", locationTypeId)

    /**
     * Tenant-scoped list search with optional area/zone/plcCode filters (L3, locations-layout
     * sprint Task 6) — mirrors [com.karyo.orders.repository.DeliveryOrderRepository.search]'s
     * dynamic-predicate style. `plcCode` is a case-insensitive contains match (search/display
     * only — zero finder semantics).
     */
    fun search(clientId: Long, areaId: Long?, zoneId: Long?, plcCode: String?, sort: Sort): PanacheQuery<StorageLocation> {
        val query = StringBuilder("clientId = :clientId")
        val params = Parameters.with("clientId", clientId)

        if (areaId != null) {
            query.append(" and area.id = :areaId")
            params.and("areaId", areaId)
        }
        if (zoneId != null) {
            query.append(" and zone.id = :zoneId")
            params.and("zoneId", zoneId)
        }
        if (!plcCode.isNullOrBlank()) {
            query.append(" and lower(plcCode) like :plcCode")
            params.and("plcCode", "%${plcCode.lowercase()}%")
        }
        return find(query.toString(), sort, params)
    }

    /**
     * Putaway-finder candidate query. Applies the cheap, indexed filters as SQL
     * predicates (filters 1-6 of the location finder, plus the L3 allocationState gate):
     *  1. area usage contains the required usage (STORAGE or PICKING) — `area.usages LIKE`
     *  2. unlocked — `lockType = 0`
     *  3. allocation < 100 (BASE allocation only; reservation load is added in-service)
     *  4. zone match when [zoneId] is given
     *  5. lifting capacity >= [weight] (null/zero capacity = unlimited)
     *  6. client ownership — shared (no rows excluded here; ownership handled in-service
     *     because the column is non-null) — see service for the shared-vs-owner rule
     *  7. allocationState = 0 (L3, locations-layout sprint Task 6) — myWMS's "mark full/
     *     blocked" operator flag; a non-zero value permanently excludes the location from
     *     auto-search regardless of allocation/lock. Default 0 on every existing row means
     *     this predicate is a no-op on a freshly migrated DB (regression pin).
     *  8. no [com.karyo.layout.domain.model.FixAssignment] on the location (LF8, location-finder
     *     sprint Task 1). Public behavioral contract:
     *     `docs/functional/location-finder.md#2-the-built-in-filter-passes--as-implemented`. A
     *     location with ANY fix assignment (any item, any client, hence no clientId in the
     *     subquery) is excluded from general putaway; stock reaches fixed locations through the
     *     replenishment flow instead.
     *
     * Ordered allocation ASC, then name ASC (stable tie-break). Entities are returned
     * (not a projection) so the service can read type/zone/area ids without extra round
     * trips; the eager joins keep it to one query (including `locationCluster`, read by
     * Task 3's StorageArea restriction/hiding logic). Capped via [limit] (the finder only
     * needs the emptiest few after reservation load is folded in).
     */
    @Suppress("LongParameterList")
    fun findPutawayCandidates(
        usageToken: String,
        zoneId: Long?,
        weight: java.math.BigDecimal,
        clientId: Long?,
        onlyClientLocation: Boolean,
        limit: Int,
    ): List<StorageLocation> {
        val sb = StringBuilder(
            "select l from StorageLocation l " +
                "join fetch l.locationType t " +
                "join fetch l.area a " +
                "left join fetch l.zone z " +
                "left join fetch l.locationCluster lc " +
                "where l.lockType = 0 " +
                "and l.allocation < 100 " +
                "and l.allocationState = 0 " +
                "and not exists (select 1 from FixAssignment f where f.location = l) " +
                "and a.usages like :usage " +
                "and (t.liftingCapacity is null or t.liftingCapacity = 0 or t.liftingCapacity >= :weight)"
        )
        val params = HashMap<String, Any>()
        params["usage"] = "%$usageToken%"
        params["weight"] = weight
        if (zoneId != null) {
            sb.append(" and z.id = :zoneId")
            params["zoneId"] = zoneId
        }
        if (clientId != null) {
            if (onlyClientLocation) {
                // strategy.onlyClientLocation = true: the requesting owner's own
                // locations ONLY — shared (client_id = 0) locations excluded.
                sb.append(" and l.clientId = :clientId")
            } else {
                // default: shared (client_id = 0 sentinel) OR owned by the requesting client
                sb.append(" and (l.clientId = 0 or l.clientId = :clientId)")
            }
            params["clientId"] = clientId
        }
        sb.append(" order by l.allocation asc, l.name asc")
        return getEntityManager()
            .createQuery(sb.toString(), StorageLocation::class.java)
            .setMaxResults(limit)
            .apply { params.forEach { (k, v) -> setParameter(k, v) } }
            .resultList
    }

    /**
     * Ids among [ids] that are unlocked (`lockType = 0`) AND whose area usage contains
     * `PICKING` -- backs [com.karyo.layout.service.AddToLocationFinder] (LF10, location-finder
     * sprint Task 7). Both the priority-1 fix-assignment gate and the priority-2 FIFO-candidate
     * gate reuse this SAME batched query for their whole candidate set, rather than touching
     * each candidate's `location`/`area` lazy association one at a time -- that per-candidate
     * shape is exactly what the batching discipline forbids (mirrors [findPutawayCandidates]'s
     * `area.usages like` predicate for the shared idiom). Mirrors [findLockedIds]'s empty-input
     * short-circuit. Unscoped by tenant -- a location's lock state and area usage are shared
     * layout config, not tenant-scoped; the stock read layered on top of these ids is what
     * applies tenant scope.
     */
    fun findUnlockedPickingIds(ids: Set<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return getEntityManager()
            .createQuery(
                "select l.id from StorageLocation l join l.area a " +
                    "where l.id in :ids and l.lockType = 0 and a.usages like :usage",
                Long::class.java,
            )
            .setParameter("ids", ids)
            .setParameter("usage", "%${AreaUsage.PICKING.name}%")
            .resultList
            .toSet()
    }

    /**
     * Location ids whose `locationCluster` is one of [clusterIds] — backs
     * [com.karyo.layout.service.AreaOccupancyReader]'s per-area occupancy read (Task 3).
     * Id-only projection (mirrors [StorageAreaRepository.clusterPairsForAreas]'s style) since
     * the caller only needs it to group a separate occupancy read, not the full entity.
     * Unfiltered by tenant — locations/clusters/areas are shared layout config, not
     * tenant-scoped; the occupancy *read* at these ids is what applies tenant scope.
     */
    fun findIdsByClusterIds(clusterIds: Collection<Long>): List<Long> {
        if (clusterIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery("select l.id from StorageLocation l where l.locationCluster.id in :clusterIds", Long::class.java)
            .setParameter("clusterIds", clusterIds)
            .resultList
    }

    /**
     * Raw `(locationClusterId, locationId)` tuples for every location whose cluster is in
     * [clusterIds] — backs [com.karyo.layout.service.DefaultItemDataAreaLookup] (R12a,
     * replenishment sprint Task 5). Unlike [findIdsByClusterIds] (flat location-id list, used
     * where the caller already queries ONE area's clusters at a time — see
     * [com.karyo.layout.service.AreaOccupancyReader]), this keeps the cluster id so the caller
     * can group the UNION of ALL areas' clusters in ONE query, then re-split per area in
     * memory — the batch discipline this SPI's `listForReplenishment` needs to stay at a
     * bounded query count regardless of how many areas are configured. Mirrors
     * [StorageAreaRepository.clusterPairsForAreas]'s tuple-select style. Unfiltered by tenant —
     * locations/clusters are shared layout config, same convention as [findIdsByClusterIds].
     */
    @Suppress("UNCHECKED_CAST")
    fun findLocationIdsGroupedByClusterIds(clusterIds: Collection<Long>): List<Array<Any>> {
        if (clusterIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery("select l.locationCluster.id, l.id from StorageLocation l where l.locationCluster.id in :clusterIds")
            .setParameter("clusterIds", clusterIds)
            .resultList as List<Array<Any>>
    }

    /**
     * Id-only projection of locations owned by [clientId] whose area usage contains `PICKING` —
     * backs [com.karyo.layout.spi.LocationAreaUsageLookup.pickingLocationIds] (R14, replenishment
     * sprint Task 3). Mirrors [findPutawayCandidates]'s `a.usages like :usage` predicate style,
     * but scoped by strict `clientId` equality rather than the shared-vs-owner branch that method
     * uses — a location's picking/storage role is tenant-owned config here, unlike the putaway
     * finder's shared-location allowance.
     */
    fun findPickingLocationIds(clientId: Long): List<Long> =
        getEntityManager()
            .createQuery(
                "select l.id from StorageLocation l join l.area a " +
                    "where a.usages like :usage and l.clientId = :clientId",
                Long::class.java,
            )
            .setParameter("usage", "%PICKING%")
            .setParameter("clientId", clientId)
            .resultList

    /**
     * Id-only projection of locations owned by [clientId] whose area usage contains
     * `CROSS_DOCK_STAGING` -- backs
     * [com.karyo.layout.spi.LocationAreaUsageLookup.crossDockStagingLocationIds]
     * (cross-docking sprint, Task 4). Mirrors [findPickingLocationIds]'s `a.usages like :usage`
     * predicate and strict `clientId` equality scoping, token swapped.
     */
    fun findCrossDockStagingLocationIds(clientId: Long): List<Long> =
        getEntityManager()
            .createQuery(
                "select l.id from StorageLocation l join l.area a " +
                    "where a.usages like :usage and l.clientId = :clientId",
                Long::class.java,
            )
            .setParameter("usage", "%CROSS_DOCK_STAGING%")
            .setParameter("clientId", clientId)
            .resultList

    /**
     * Raw tuple projection (id, areaId, rack, field, section) for EVERY location in [areaIds] —
     * backs [com.karyo.layout.service.GroupCapacityReader]'s field/section group-membership
     * resolution (locations-layout sprint Task 7, L4). Fetches every location in the affected
     * area(s) in ONE query (never per-candidate or per-group) so the reader groups them in
     * memory by (areaId, rack, field) / (areaId, section). Unfiltered by tenant — locations are
     * shared layout config, mirroring [findIdsByClusterIds].
     */
    @Suppress("UNCHECKED_CAST")
    fun findGroupMembersByAreaIds(areaIds: Collection<Long>): List<Array<Any?>> {
        if (areaIds.isEmpty()) return emptyList()
        return getEntityManager()
            .createQuery("select l.id, l.area.id, l.rack, l.field, l.section from StorageLocation l where l.area.id in :areaIds")
            .setParameter("areaIds", areaIds)
            .resultList as List<Array<Any?>>
    }
}

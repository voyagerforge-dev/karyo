package com.karyo.layout.service

import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.domain.model.StorageStrategy
import com.karyo.layout.domain.model.TypeCapacityConstraint
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageStrategyAreaRepository
import com.karyo.layout.repository.ZoneRepository
import com.karyo.layout.spi.LocationFinderRequest
import com.karyo.layout.vo.StorageStrategySortType
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Builds and applies the [StorageStrategy.sorts]-driven comparator chain (locations-layout
 * sprint Task 5), run on the surviving candidate list strictly BEFORE the SPI
 * [com.karyo.layout.spi.LocationFilter] chain — see [LocationFinderService]'s class KDoc for
 * the "no re-sort after SPI filters" contract this must not violate. Split out of
 * [LocationFinderService] purely to keep that class's methods within the project's Detekt
 * size limits (same reasoning as [AreaOccupancyReader]).
 *
 * **Regression pin:** an empty `sorts` list AND `nearPickingLocation == false` (or no
 * resolvable [com.karyo.layout.domain.model.FixAssignment]) means [apply] returns
 * [candidates] UNCHANGED — the finder's existing filter-9 ordering (Task 3's area preference,
 * effective allocation ASC, name ASC) is the entire story, byte-for-byte identical to before
 * this task.
 *
 * **Precedence (highest first) when either mechanism is active:**
 *  1. `nearPickingLocation` distance (if the incoming product has a resolvable
 *     [com.karyo.layout.domain.model.FixAssignment]) — physical proximity to the fixed pick
 *     face outranks every other criterion when requested.
 *  2. **Area-order prepend** (final-review F2 fix): when `strategy.useAreaStrategyDate` is
 *     active with >1 configured areas, the area's `orderIndex` in the strategy's ordered
 *     area list is prepended here — BEFORE the `sorts` chain — so [apply]'s fresh
 *     `sortedWith` cannot destroy [LocationFinderService.applyAreaConstraints] (Task 3)'s own
 *     area-order-primary ordering the way an unconditional re-sort otherwise would. This is
 *     what makes the `STORAGEAREA` skip below genuinely redundant rather than a silent loss.
 *  3. Each [StorageStrategySortType] in `sorts`, IN LIST ORDER (the CSV order is the
 *     priority order) — `STORAGEAREA` is SKIPPED when `strategy.useAreaStrategyDate` is
 *     true, because step 2 already prepends that exact ordering ahead of this chain;
 *     listing it again would be redundant, not conflicting, but the brief calls for an
 *     explicit skip.
 *  4. `name` ASC, appended UNLESS `NAME` already appears in `sorts`.
 *  5. `id` ASC — final, total-order tiebreak.
 *
 * Because [apply] does a single fresh `sortedWith` (not a `thenBy` chained onto the incoming
 * order), Kotlin's stable sort means [candidates]' incoming order (Task 3's own ordering)
 * only matters as an ultimate fallback for ties across ALL of the above — which name+id
 * already rule out (and, when active, step 2 above already restores the area-order part of
 * that ordering explicitly), so it is effectively unreachable, but harmless.
 */
@ApplicationScoped
class CandidateOrdering(
    private val strategyAreaRepository: StorageStrategyAreaRepository,
    private val storageAreaService: StorageAreaService,
    private val fixAssignmentRepository: FixAssignmentRepository,
    private val zoneRepository: ZoneRepository,
) {

    fun apply(
        candidates: List<Pair<StorageLocation, BigDecimal>>,
        request: LocationFinderRequest,
        strategy: StorageStrategy?,
        constraintsByType: Map<Long, List<TypeCapacityConstraint>>,
        resolvedZoneId: Long?,
    ): List<Pair<StorageLocation, BigDecimal>> {
        val sortTypes = StorageStrategySortParser.parseLenient(strategy?.sorts)
        val distanceComparator = nearPickingComparator(strategy, request)
        if (sortTypes.isEmpty() && distanceComparator == null) return candidates

        // F4 (final review): both maps stay `Lazy` handles passed down uncomputed -- only the
        // branch that actually needs one (STORAGEAREA/ZONE in `sorts`, or the F2 prepend below
        // when `useAreaStrategyDate` is active) ever calls `.value`, so e.g. a plain `sorts =
        // "NAME"` strategy no longer pays for Task 3's area/zone queries at all.
        val storageAreaOrder: Lazy<Map<Long, Int>> = lazy { unconditionalAreaOrderByCluster(strategy) }
        val zoneRank: Lazy<Map<Long, Int>> = lazy { zoneChainRank(strategy, resolvedZoneId) }

        val comparators = mutableListOf<Comparator<Pair<StorageLocation, BigDecimal>>>()
        distanceComparator?.let { comparators += it }

        // F2 (final review): when useAreaStrategyDate is active, prepend the SAME area-order
        // ahead of `sorts` that LocationFinderService.applyAreaConstraints (Task 3) already
        // applies -- otherwise this class's fresh `sortedWith` below silently destroys that
        // ordering (and the STORAGEAREA skip a few lines down would then be a genuine loss,
        // not the harmless redundancy it's meant to be). Degenerates to a no-op when only one
        // area is configured (every cluster maps to index 0), so no separate ">1 areas" guard
        // is needed here -- `storageAreaOrder.value.isNotEmpty()` alone is the correct gate.
        if (strategy?.useAreaStrategyDate == true && storageAreaOrder.value.isNotEmpty()) {
            comparators += clusterOrderComparator(storageAreaOrder.value)
        }

        sortTypes.forEach { type ->
            if (type == StorageStrategySortType.STORAGEAREA && strategy?.useAreaStrategyDate == true) {
                return@forEach
            }
            comparators += comparatorFor(type, request, constraintsByType, storageAreaOrder, zoneRank)
        }
        if (StorageStrategySortType.NAME !in sortTypes) {
            comparators += compareBy { (loc, _) -> loc.name }
        }
        comparators += compareBy { (loc, _) -> loc.id }

        return candidates.sortedWith(comparators.reduce { acc, next -> acc.then(next) })
    }

    @Suppress("CyclomaticComplexMethod")
    private fun comparatorFor(
        type: StorageStrategySortType,
        request: LocationFinderRequest,
        constraintsByType: Map<Long, List<TypeCapacityConstraint>>,
        storageAreaOrder: Lazy<Map<Long, Int>>,
        zoneRank: Lazy<Map<Long, Int>>,
    ): Comparator<Pair<StorageLocation, BigDecimal>> = when (type) {
        StorageStrategySortType.CLIENT -> compareBy { (loc, _) -> clientRank(loc, request.clientId) }
        StorageStrategySortType.STORAGEAREA -> clusterOrderComparator(storageAreaOrder.value)
        StorageStrategySortType.ZONE -> compareBy { (loc, _) -> loc.zone?.id?.let { zoneRank.value[it] } ?: Int.MAX_VALUE }
        StorageStrategySortType.CAPACITY -> compareBy { (loc, _) -> capacityOrderIndex(loc, request, constraintsByType) }
        StorageStrategySortType.ALLOCATION -> compareByDescending { (_, eff) -> eff }
        StorageStrategySortType.POSITION_X -> compareBy { (loc, _) -> loc.xPos }
        StorageStrategySortType.POSITION_Y -> compareBy { (loc, _) -> loc.yPos }
        StorageStrategySortType.NAME -> compareBy { (loc, _) -> loc.name }
        StorageStrategySortType.ORDERINDEX -> compareBy { (loc, _) -> loc.orderIndex }
    }

    /** Shared `locationCluster.id -> orderIndex` comparator (unmapped clusters rank last) --
     * used both by the F2 area-order prepend and the `STORAGEAREA` sort type itself, since
     * the two are now literally the same ordering. */
    private fun clusterOrderComparator(order: Map<Long, Int>): Comparator<Pair<StorageLocation, BigDecimal>> =
        compareBy { (loc, _) -> loc.locationCluster?.id?.let { order[it] } ?: Int.MAX_VALUE }

    /** myWMS `nearPickingLocation`: when active AND the incoming product resolves to at
     * least one [com.karyo.layout.domain.model.FixAssignment] (ties broken by the lowest
     * `orderIndex`), prepend a same-rack-first, then-by-X-distance comparator.
     * `strategy.nearPickingLocation == false`, no [LocationFinderRequest.itemDataId], or no
     * matching fix assignment → returns `null` (nothing prepended, per the brief).
     *
     * Public behavioral contract: `docs/functional/location-finder.md#3-ordering--tie-break`.
     * This restructures the predecessor's two-phase rack-restricted-then-general search into a
     * single-pass preference:
     * same-rack candidates sort ahead, X-distance breaks ties, and a full rack falls back to
     * the general ordering instead of failing, which reproduces the legacy outcome inside
     * Karyo's one-pass model (deliberate structural divergence, do not harden into a
     * restriction). */
    private fun nearPickingComparator(
        strategy: StorageStrategy?,
        request: LocationFinderRequest,
    ): Comparator<Pair<StorageLocation, BigDecimal>>? {
        if (strategy?.nearPickingLocation != true) return null
        val itemDataId = request.itemDataId ?: return null
        val clientId = request.clientId ?: return null
        val fix = fixAssignmentRepository.findByItemDataId(itemDataId, clientId)
            .minByOrNull { it.orderIndex } ?: return null
        val fixX = fix.location.xPos
        val fixRack = fix.location.rack
        val distance = compareBy<Pair<StorageLocation, BigDecimal>> { (loc, _) -> abs(loc.xPos - fixX) }
        return if (fixRack == null) {
            distance
        } else {
            compareBy<Pair<StorageLocation, BigDecimal>> { (loc, _) -> if (loc.rack == fixRack) 0 else 1 }
                .then(distance)
        }
    }

    /** Owner-first ranking mirroring filter 6's ownership model: the requesting owner's own
     * (dedicated) locations first, then shared (`clientId == 0`), then anything else a
     * `mixClient` strategy still allowed through. */
    private fun clientRank(loc: StorageLocation, requestClientId: Long?): Int = when {
        requestClientId != null && loc.clientId == requestClientId -> 0
        loc.clientId == 0L -> 1
        else -> 2
    }

    private fun capacityOrderIndex(
        loc: StorageLocation,
        request: LocationFinderRequest,
        constraintsByType: Map<Long, List<TypeCapacityConstraint>>,
    ): Int {
        val rows = constraintsByType[loc.locationType.id!!] ?: return Int.MAX_VALUE
        val matching = rows.firstOrNull { it.unitLoadTypeId == request.unitLoadTypeId } ?: return Int.MAX_VALUE
        return matching.orderIndex
    }

    /** The `STORAGEAREA` sort type's own cluster->orderIndex map — UNCONDITIONAL on
     * `useAreaStrategyDate` (unlike [AreaOccupancyReader]'s identically-shaped map, which only
     * populates when that flag is active with >1 areas). This is what lets a strategy opt
     * into area-preference ordering via `sorts` alone, without also turning on cross-area
     * FIFO hiding. */
    private fun unconditionalAreaOrderByCluster(strategy: StorageStrategy?): Map<Long, Int> {
        val orderedAreas = strategy?.id?.let { strategyAreaRepository.orderedByStrategy(it) } ?: emptyList()
        if (orderedAreas.isEmpty()) return emptyMap()
        val clustersByArea = storageAreaService.clustersForAreas(orderedAreas.map { it.storageArea.id!! })
        val result = mutableMapOf<Long, Int>()
        orderedAreas.forEachIndexed { idx, sa ->
            (clustersByArea[sa.storageArea.id!!] ?: emptySet()).forEach { clusterId -> result.putIfAbsent(clusterId, idx) }
        }
        return result
    }

    /** `ZONE` sort type: rank = position in the chain starting at the resolved zone
     * (`request.preferredZoneId ?: strategy.zone`) and following [com.karyo.layout.domain.model.Zone.overflowZone]
     * links in order (0 = the strategy's own zone, 1 = its first overflow, ...). Bounded to
     * guard against a misconfigured cycle; a zone outside the chain (or no zone resolved at
     * all) ranks last via the caller's `?: Int.MAX_VALUE` fallback. */
    private fun zoneChainRank(strategy: StorageStrategy?, resolvedZoneId: Long?): Map<Long, Int> {
        val startZoneId = resolvedZoneId ?: strategy?.zone?.id ?: return emptyMap()
        val rank = mutableMapOf<Long, Int>()
        val visited = mutableSetOf<Long>()
        var currentId: Long? = startZoneId
        var idx = 0
        while (currentId != null && currentId !in visited && idx < MAX_ZONE_CHAIN) {
            rank[currentId] = idx
            visited += currentId
            currentId = zoneRepository.findById(currentId)?.overflowZone?.id
            idx++
        }
        return rank
    }

    companion object {
        private const val MAX_ZONE_CHAIN = 20
    }
}

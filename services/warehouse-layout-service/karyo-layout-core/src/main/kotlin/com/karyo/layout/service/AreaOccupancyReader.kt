package com.karyo.layout.service

import com.karyo.inventory.api.spi.StockOccupancyRef
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.domain.model.ItemDataArea
import com.karyo.layout.domain.model.StorageStrategyArea
import com.karyo.layout.repository.ItemDataAreaRepository
import com.karyo.layout.repository.StorageLocationRepository
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

/**
 * Computes the [LocationFinderService] built-in area logic (locations-layout sprint Task 3):
 * which of a strategy's ordered [StorageStrategyArea]s to hide (`useAreaStrategyDate` cross-area
 * FIFO + `useItemDataArea` full-area), and the area-order preference candidates should sort by
 * while [StorageStrategy.useAreaStrategyDate][com.karyo.layout.domain.model.StorageStrategy.useAreaStrategyDate]
 * is active. Split out of [LocationFinderService] purely to keep that class's methods within
 * the project's Detekt size limits. Kotlin has no package-private visibility, and this bean is
 * constructor-injected into the (necessarily public) [LocationFinderService], so it is `public`
 * rather than `internal` — an `internal` type cannot appear in a public constructor's
 * signature. It is still not exported from `karyo-layout-api`, which is the practical
 * "package-private" intent the brief asked for.
 *
 * Both hiding rules need to know how much of the incoming product is *already on hand* in each
 * area's clusters — that occupancy read is the one cross-module call this class makes, via
 * [StockUnitLookup.occupancyByLocationIds] (inventory-api SPI, already consumed elsewhere in
 * this module — no new Gradle edge).
 */
@ApplicationScoped
class AreaOccupancyReader(
    private val storageAreaService: StorageAreaService,
    private val locationRepository: StorageLocationRepository,
    private val itemDataAreaRepository: ItemDataAreaRepository,
    private val stockUnitLookup: StockUnitLookup,
) {

    /**
     * Resolves the area constraints for one putaway search. [orderedAreas] must already be
     * sorted by `orderIndex` ascending (as [com.karyo.layout.repository.StorageStrategyAreaRepository.orderedByStrategy]
     * returns it) — empty means "no areas configured", which the caller handles before ever
     * reaching this method (see [LocationFinderService]'s KDoc on the (a) restriction rule).
     */
    fun resolve(
        orderedAreas: List<StorageStrategyArea>,
        useAreaStrategyDate: Boolean,
        useItemDataArea: Boolean,
        itemDataId: Long?,
        strategyDate: Instant?,
    ): AreaConstraints {
        if (orderedAreas.isEmpty()) return AreaConstraints(emptySet(), emptyMap())

        val clustersByArea = storageAreaService.clustersForAreas(orderedAreas.map { it.storageArea.id!! })
        val locationIdsByArea = orderedAreas.associate { sa ->
            sa.storageArea.id!! to locationRepository.findIdsByClusterIds(clustersByArea[sa.storageArea.id!!] ?: emptySet())
        }

        val hiddenAreaIds = mutableSetOf<Long>()
        if (itemDataId != null) {
            val occupancy = occupancyForProduct(locationIdsByArea, itemDataId)
            if (useAreaStrategyDate && orderedAreas.size > 1) {
                hiddenAreaIds += fifoHiddenAreaIds(orderedAreas, locationIdsByArea, occupancy, strategyDate)
            }
            if (useItemDataArea) {
                hiddenAreaIds += fullAreaIds(orderedAreas, locationIdsByArea, occupancy, itemDataId)
            }
        }

        val allowedClusterIds = orderedAreas
            .filter { it.storageArea.id!! !in hiddenAreaIds }
            .flatMap { clustersByArea[it.storageArea.id!!] ?: emptySet() }
            .toSet()

        val areaOrderByCluster = if (useAreaStrategyDate && orderedAreas.size > 1) {
            areaOrderByCluster(orderedAreas, clustersByArea)
        } else {
            emptyMap()
        }

        return AreaConstraints(allowedClusterIds, areaOrderByCluster)
    }

    private fun occupancyForProduct(locationIdsByArea: Map<Long, List<Long>>, itemDataId: Long): List<StockOccupancyRef> {
        val allLocationIds = locationIdsByArea.values.flatten().toSet()
        if (allLocationIds.isEmpty()) return emptyList()
        return stockUnitLookup.occupancyByLocationIds(allLocationIds).filter { it.itemDataId == itemDataId }
    }

    /**
     * (b) `useAreaStrategyDate`: walking the ordered areas, find the FIRST one already holding
     * same-product stock with an OLDER [strategyDate] than the incoming stock's — every area
     * AFTER it (higher `orderIndex`) is hidden. `null` [strategyDate] (unknown incoming FIFO
     * date) disables the comparison entirely (no hiding), rather than guessing "older than
     * unknown".
     */
    private fun fifoHiddenAreaIds(
        orderedAreas: List<StorageStrategyArea>,
        locationIdsByArea: Map<Long, List<Long>>,
        occupancy: List<StockOccupancyRef>,
        strategyDate: Instant?,
    ): Set<Long> {
        if (strategyDate == null) return emptySet()
        val firstOlderIndex = orderedAreas.indexOfFirst { sa ->
            val areaLocationIds = locationIdsByArea[sa.storageArea.id!!]?.toSet() ?: emptySet()
            occupancy.any { ref ->
                val refDate = ref.strategyDate
                ref.locationId in areaLocationIds && refDate != null && refDate.isBefore(strategyDate)
            }
        }
        if (firstOlderIndex < 0) return emptySet()
        return orderedAreas.drop(firstOlderIndex + 1).map { it.storageArea.id!! }.toSet()
    }

    /** (c) `useItemDataArea`: an area is full — and hidden — when every one of its defined
     * (>0) [ItemDataArea] thresholds is met by current same-product occupancy. No threshold
     * row for an area at all means "not tracked", never full. */
    private fun fullAreaIds(
        orderedAreas: List<StorageStrategyArea>,
        locationIdsByArea: Map<Long, List<Long>>,
        occupancy: List<StockOccupancyRef>,
        itemDataId: Long,
    ): Set<Long> {
        val areaIds = orderedAreas.map { it.storageArea.id!! }
        val thresholds = itemDataAreaRepository.findByItemAndAreas(itemDataId, areaIds).associateBy { it.storageArea.id!! }
        if (thresholds.isEmpty()) return emptySet()

        return areaIds.filter { areaId ->
            val threshold = thresholds[areaId] ?: return@filter false
            val areaLocationIds = locationIdsByArea[areaId]?.toSet() ?: emptySet()
            isFull(threshold, occupancy.filter { it.locationId in areaLocationIds })
        }.toSet()
    }

    private fun isFull(threshold: ItemDataArea, areaOccupancy: List<StockOccupancyRef>): Boolean {
        val checks = mutableListOf<Boolean>()
        threshold.plannedStocks?.takeIf { it > 0 }?.let { limit ->
            checks += areaOccupancy.mapNotNull { it.unitLoadId }.toSet().size >= limit
        }
        threshold.plannedAmount?.takeIf { it > BigDecimal.ZERO }?.let { limit ->
            checks += areaOccupancy.fold(BigDecimal.ZERO) { acc, ref -> acc + ref.amount } >= limit
        }
        return checks.isNotEmpty() && checks.all { it }
    }

    /** Cluster id -> the index (0-based) of the FIRST area in [orderedAreas] that claims it —
     * backs the "areas earlier in the list first" candidate-ordering preference. */
    private fun areaOrderByCluster(orderedAreas: List<StorageStrategyArea>, clustersByArea: Map<Long, Set<Long>>): Map<Long, Int> {
        val result = mutableMapOf<Long, Int>()
        orderedAreas.forEachIndexed { idx, sa ->
            (clustersByArea[sa.storageArea.id!!] ?: emptySet()).forEach { clusterId -> result.putIfAbsent(clusterId, idx) }
        }
        return result
    }
}

/**
 * @param allowedClusterIds the union of cluster ids a candidate location's `locationCluster`
 *        must belong to (already excludes hidden areas' clusters). Empty when areas are
 *        configured but every one is hidden or clusterless — that legitimately empties the
 *        candidate set (a NoLocation the caller surfaces with a named reason).
 * @param areaOrderByCluster cluster id -> area-order rank, non-empty only when
 *        `useAreaStrategyDate` is active with >1 configured area.
 */
data class AreaConstraints(
    val allowedClusterIds: Set<Long>,
    val areaOrderByCluster: Map<Long, Int>,
)

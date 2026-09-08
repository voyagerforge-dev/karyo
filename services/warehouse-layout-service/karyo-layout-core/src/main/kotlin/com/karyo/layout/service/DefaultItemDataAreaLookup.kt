package com.karyo.layout.service

import com.karyo.layout.domain.model.ItemDataArea
import com.karyo.layout.repository.ItemDataAreaRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.ItemDataAreaLookup
import com.karyo.layout.spi.ItemDataAreaView
import com.karyo.product.spi.ProductLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process implementation of [ItemDataAreaLookup] (R12a, replenishment sprint
 * Task 5). Explicit `clientId` throughout — see the interface KDoc for why.
 *
 * **Bounded query count, independent of area/item count:**
 * 1. [ItemDataAreaRepository.findByClientId] — every configured row for the tenant.
 * 2. [StorageAreaService.clustersForAreas] — batched area -> cluster-id-set map (already ONE
 *    query for every area in the input, per that method's own KDoc).
 * 3. [StorageLocationRepository.findLocationIdsGroupedByClusterIds] — ONE query over the UNION
 *    of every area's cluster ids, grouped back to per-cluster location-id sets in memory (not
 *    one call per area, unlike [AreaOccupancyReader]'s per-area loop — that reader resolves one
 *    area at a time by design, this lookup resolves every area for the tenant in one pass).
 * 4. [ProductLookup.findNumbersByIds] — one batched, explicit-`clientId` SKU lookup for every
 *    distinct `itemDataId` referenced.
 *
 * Total: 4 queries regardless of how many [ItemDataArea] rows exist.
 */
@ApplicationScoped
class DefaultItemDataAreaLookup(
    private val itemDataAreaRepository: ItemDataAreaRepository,
    private val storageAreaService: StorageAreaService,
    private val storageLocationRepository: StorageLocationRepository,
    private val productLookup: ProductLookup,
) : ItemDataAreaLookup {

    override fun listForReplenishment(clientId: Long): List<ItemDataAreaView> {
        val entities = itemDataAreaRepository.findByClientId(clientId)
        if (entities.isEmpty()) return emptyList()

        val areaIds = entities.map { it.storageArea.id!! }.distinct()
        val clustersByArea = storageAreaService.clustersForAreas(areaIds)
        val allClusterIds = clustersByArea.values.flatten().toSet()

        val locationIdsByCluster = storageLocationRepository.findLocationIdsGroupedByClusterIds(allClusterIds)
            .groupBy({ it[0] as Long }, { it[1] as Long })
            .mapValues { it.value.toSet() }

        val numbers = productLookup.findNumbersByIds(entities.map { it.itemDataId }.toSet(), clientId)

        return entities.map { entity -> toView(entity, clustersByArea, locationIdsByCluster, numbers) }
    }

    private fun toView(
        entity: ItemDataArea,
        clustersByArea: Map<Long, Set<Long>>,
        locationIdsByCluster: Map<Long, Set<Long>>,
        numbers: Map<Long, String>,
    ): ItemDataAreaView {
        val areaId = entity.storageArea.id!!
        val clusterIds = clustersByArea[areaId] ?: emptySet()
        val locationIds = clusterIds.flatMap { locationIdsByCluster[it] ?: emptySet() }.toSet()

        return ItemDataAreaView(
            itemDataAreaId = entity.id!!,
            itemDataId = entity.itemDataId,
            itemDataNumber = numbers[entity.itemDataId] ?: "ITEM-${entity.itemDataId}",
            storageAreaId = areaId,
            plannedAmount = entity.plannedAmount,
            plannedStocks = entity.plannedStocks,
            clusterLocationIds = locationIds,
        )
    }
}

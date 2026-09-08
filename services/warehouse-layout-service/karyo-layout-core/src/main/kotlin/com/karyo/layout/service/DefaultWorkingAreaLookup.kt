package com.karyo.layout.service

import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.repository.WorkingAreaRepository
import com.karyo.layout.spi.WorkingAreaLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default implementation of [WorkingAreaLookup]. Resolves member clusters → location ids
 * in two steps, reusing existing id-only projections rather than adding a new joined query:
 * [WorkingAreaRepository.clusterIdsFor] (mirrors [StorageAreaRepository.clusterPairsForAreas])
 * then [StorageLocationRepository.findIdsByClusterIds] (the same query Task 3's
 * `AreaOccupancyReader` uses for `StorageArea`).
 */
@ApplicationScoped
class DefaultWorkingAreaLookup(
    private val workingAreaRepository: WorkingAreaRepository,
    private val storageLocationRepository: StorageLocationRepository,
) : WorkingAreaLookup {

    override fun locationIdsFor(workingAreaId: Long): Set<Long> {
        val clusterIds = workingAreaRepository.clusterIdsFor(workingAreaId)
        if (clusterIds.isEmpty()) return emptySet()
        return storageLocationRepository.findIdsByClusterIds(clusterIds).toSet()
    }

    override fun exists(id: Long): Boolean = workingAreaRepository.findById(id) != null
}

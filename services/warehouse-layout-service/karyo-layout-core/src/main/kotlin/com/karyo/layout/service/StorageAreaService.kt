package com.karyo.layout.service

import com.karyo.layout.domain.model.LocationCluster
import com.karyo.layout.domain.model.StorageArea
import com.karyo.layout.dto.CreateStorageAreaRequest
import com.karyo.layout.dto.StorageAreaResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.ItemDataAreaRepository
import com.karyo.layout.repository.LocationClusterRepository
import com.karyo.layout.repository.StorageAreaRepository
import com.karyo.layout.repository.StorageStrategyAreaRepository
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class StorageAreaService(
    private val storageAreaRepository: StorageAreaRepository,
    private val locationClusterRepository: LocationClusterRepository,
    private val storageStrategyAreaRepository: StorageStrategyAreaRepository,
    private val itemDataAreaRepository: ItemDataAreaRepository,
) {

    fun listAll(): List<StorageAreaResponse> =
        storageAreaRepository.listAll().map { toResponse(it) }

    @CacheResult(cacheName = "storage-areas")
    fun findById(@CacheKey id: Long): StorageAreaResponse {
        val entity = storageAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageArea", id)
        return toResponse(entity)
    }

    @Transactional
    fun create(request: CreateStorageAreaRequest): StorageAreaResponse {
        storageAreaRepository.findByName(request.name)?.let {
            throw LayoutException.DuplicateName("StorageArea", request.name)
        }

        val entity = StorageArea().apply {
            name = request.name
            clusters = resolveClusters(request.clusterIds).toMutableSet()
            transferStaging = request.transferStaging
        }

        storageAreaRepository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: CreateStorageAreaRequest): StorageAreaResponse {
        val entity = storageAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageArea", id)

        val existing = storageAreaRepository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("StorageArea", request.name)
        }

        entity.name = request.name
        entity.clusters = resolveClusters(request.clusterIds).toMutableSet()
        entity.transferStaging = request.transferStaging

        invalidateById(id)
        return toResponse(entity)
    }

    @Transactional
    fun delete(id: Long) {
        storageAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageArea", id)

        assertNoDependents(id)

        invalidateById(id)
        storageAreaRepository.deleteById(id)
    }

    private fun assertNoDependents(id: Long) {
        val strategyUsage = storageStrategyAreaRepository.countByArea(id)
        if (strategyUsage > 0) {
            throw LayoutException.HasDependents("StorageArea", id, "storage strategy areas")
        }
        val itemUsage = itemDataAreaRepository.countByArea(id)
        if (itemUsage > 0) {
            throw LayoutException.HasDependents("StorageArea", id, "item data areas")
        }
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "storage-areas")
    fun invalidateById(@CacheKey id: Long) {
        // Cache invalidation handled by annotation
    }

    /**
     * Produced interface (Task 1, consumed by Tasks 3/5): cluster ids per area. **Areas
     * with no assigned clusters are absent from the map** (never an empty-set entry) —
     * callers must treat a missing key as "no clusters", not as an error.
     */
    fun clustersForAreas(areaIds: List<Long>): Map<Long, Set<Long>> =
        storageAreaRepository.clusterPairsForAreas(areaIds)
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    private fun resolveClusters(clusterIds: List<Long>): List<LocationCluster> {
        val ids = clusterIds.distinct()
        if (ids.isEmpty()) return emptyList()

        val found = locationClusterRepository.list("id in ?1", ids)
        val foundIds = found.mapNotNull { it.id }.toSet()
        val missing = ids.filter { it !in foundIds }
        if (missing.isNotEmpty()) {
            throw LayoutException.InvalidReferenceList("StorageArea", "LocationCluster", missing)
        }
        return found
    }

    private fun toResponse(entity: StorageArea): StorageAreaResponse = StorageAreaResponse(
        id = entity.id!!,
        name = entity.name,
        clusterIds = entity.clusters.mapNotNull { it.id },
        created = entity.created.toString(),
        modified = entity.modified.toString(),
        transferStaging = entity.transferStaging,
    )
}

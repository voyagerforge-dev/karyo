package com.karyo.layout.service

import com.karyo.layout.domain.model.StorageStrategy
import com.karyo.layout.domain.model.StorageStrategyArea
import com.karyo.layout.dto.CreateStorageStrategyRequest
import com.karyo.layout.dto.StorageStrategyResponse
import com.karyo.layout.dto.StrategyAreaResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.StorageAreaRepository
import com.karyo.layout.repository.StorageStrategyAreaRepository
import com.karyo.layout.repository.StorageStrategyRepository
import com.karyo.layout.repository.ZoneRepository
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class StorageStrategyService(
    private val storageStrategyRepository: StorageStrategyRepository,
    private val zoneRepository: ZoneRepository,
    private val storageAreaRepository: StorageAreaRepository,
    private val storageStrategyAreaRepository: StorageStrategyAreaRepository,
) {

    fun listByClient(clientId: Long): List<StorageStrategyResponse> =
        storageStrategyRepository.findByClientId(clientId).map { toStrategyResponse(it) }

    @CacheResult(cacheName = "storage-strategies")
    fun findById(@CacheKey id: Long, @CacheKey clientId: Long): StorageStrategyResponse {
        val entity = storageStrategyRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageStrategy", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("StorageStrategy", id)
        }
        return toStrategyResponse(entity)
    }

    @Transactional
    fun create(request: CreateStorageStrategyRequest, clientId: Long): StorageStrategyResponse {
        storageStrategyRepository.findByName(request.name, clientId)?.let {
            throw LayoutException.DuplicateName("StorageStrategy", request.name)
        }

        val zoneId = request.zoneId
        if (zoneId != null) {
            zoneRepository.findById(zoneId)
                ?: throw LayoutException.InvalidReference("StorageStrategy", "Zone", zoneId)
        }
        StorageStrategySortParser.validate(request.sorts)

        val entity = StorageStrategy().apply {
            name = request.name
            zone = zoneId?.let { zoneRepository.findById(it) }
            mixItem = request.mixItem
            mixClient = request.mixClient
            nearPickingLocation = request.nearPickingLocation
            sorts = request.sorts
            onlyClientLocation = request.onlyClientLocation
            manualSearch = request.manualSearch
            useAreaStrategyDate = request.useAreaStrategyDate
            useItemDataArea = request.useItemDataArea
            this.clientId = clientId
        }

        storageStrategyRepository.persist(entity)
        return toStrategyResponse(entity)
    }

    @Suppress("ThrowsCount")
    @Transactional
    fun update(id: Long, request: CreateStorageStrategyRequest, clientId: Long): StorageStrategyResponse {
        val entity = storageStrategyRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageStrategy", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("StorageStrategy", id)
        }

        val existing = storageStrategyRepository.findByName(request.name, clientId)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("StorageStrategy", request.name)
        }

        val updateZoneId = request.zoneId
        if (updateZoneId != null) {
            zoneRepository.findById(updateZoneId)
                ?: throw LayoutException.InvalidReference("StorageStrategy", "Zone", updateZoneId)
        }
        StorageStrategySortParser.validate(request.sorts)

        entity.name = request.name
        entity.zone = updateZoneId?.let { zoneRepository.findById(it) }
        entity.mixItem = request.mixItem
        entity.mixClient = request.mixClient
        entity.nearPickingLocation = request.nearPickingLocation
        entity.sorts = request.sorts
        entity.onlyClientLocation = request.onlyClientLocation
        entity.manualSearch = request.manualSearch
        entity.useAreaStrategyDate = request.useAreaStrategyDate
        entity.useItemDataArea = request.useItemDataArea

        invalidateById(id, clientId)
        return toStrategyResponse(entity)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "storage-strategies")
    fun invalidateById(@CacheKey id: Long, @CacheKey clientId: Long) {
        // Cache invalidation handled by annotation
    }

    // ── L1: ordered storage-area assignment (myWMS `saveForStorageStrategy` shape) ──────

    fun getAreas(strategyId: Long, clientId: Long): List<StrategyAreaResponse> {
        findEntityForClient(strategyId, clientId)
        return orderedAreaResponses(strategyId)
    }

    /**
     * Rewrites the strategy's ENTIRE ordered area list, assigning `orderIndex` 1..N by
     * input order. Validates every id exists BEFORE deleting/inserting anything, so a
     * failure (unknown or duplicate area id) leaves the previous list completely untouched
     * ("no partial write").
     */
    @Transactional
    fun setAreas(strategyId: Long, areaIds: List<Long>, clientId: Long): List<StrategyAreaResponse> {
        val strategy = findEntityForClient(strategyId, clientId)

        val distinctIds = areaIds.distinct()
        if (distinctIds.size != areaIds.size) {
            val duplicates = areaIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
            throw LayoutException.InvalidReferenceList("StorageStrategy", "StorageArea (duplicate)", duplicates)
        }

        val areas = storageAreaRepository.findByIds(distinctIds)
        val areaById = areas.associateBy { it.id!! }
        val missing = distinctIds.filter { it !in areaById.keys }
        if (missing.isNotEmpty()) {
            throw LayoutException.InvalidReferenceList("StorageStrategy", "StorageArea", missing)
        }

        storageStrategyAreaRepository.deleteByStrategy(strategyId)
        distinctIds.forEachIndexed { index, areaId ->
            val entry = StorageStrategyArea().apply {
                this.storageStrategy = strategy
                this.storageArea = areaById.getValue(areaId)
                this.orderIndex = index + 1
            }
            storageStrategyAreaRepository.persist(entry)
        }

        invalidateById(strategyId, clientId)
        return orderedAreaResponses(strategyId)
    }

    private fun findEntityForClient(id: Long, clientId: Long): StorageStrategy {
        val entity = storageStrategyRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageStrategy", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("StorageStrategy", id)
        }
        return entity
    }

    private fun orderedAreaResponses(strategyId: Long): List<StrategyAreaResponse> =
        storageStrategyAreaRepository.orderedByStrategy(strategyId).map {
            StrategyAreaResponse(id = it.storageArea.id!!, name = it.storageArea.name, orderIndex = it.orderIndex)
        }

    fun toStrategyResponse(entity: StorageStrategy): StorageStrategyResponse = StorageStrategyResponse(
        id = entity.id!!,
        name = entity.name,
        zoneId = entity.zone?.id,
        mixItem = entity.mixItem,
        mixClient = entity.mixClient,
        nearPickingLocation = entity.nearPickingLocation,
        sorts = entity.sorts,
        onlyClientLocation = entity.onlyClientLocation,
        manualSearch = entity.manualSearch,
        useAreaStrategyDate = entity.useAreaStrategyDate,
        useItemDataArea = entity.useItemDataArea,
        areas = orderedAreaResponses(entity.id!!),
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )
}

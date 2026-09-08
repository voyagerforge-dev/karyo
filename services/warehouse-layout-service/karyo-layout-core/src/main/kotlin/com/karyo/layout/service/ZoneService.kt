package com.karyo.layout.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.layout.domain.model.Zone
import com.karyo.layout.dto.CreateZoneRequest
import com.karyo.layout.dto.ZoneResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.repository.ZoneRepository
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class ZoneService(
    private val zoneRepository: ZoneRepository,
    private val locationRepository: StorageLocationRepository,
) {

    fun listAll(): List<ZoneResponse> =
        zoneRepository.listAll().map { toZoneResponse(it) }

    fun listAllPaginated(pagination: PaginationParams): PaginatedResponse<ZoneResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.ascending("name"))
        val query = zoneRepository.findAll(sort).page(Page.of(pagination.page, pagination.size))
        val content = query.list().map { toZoneResponse(it) }
        val totalElements = query.count()
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    @CacheResult(cacheName = "zones")
    fun findById(@CacheKey id: Long): ZoneResponse {
        val entity = zoneRepository.findById(id)
            ?: throw LayoutException.NotFound("Zone", id)
        return toZoneResponse(entity)
    }

    @Transactional
    fun create(request: CreateZoneRequest): ZoneResponse {
        zoneRepository.findByName(request.name)?.let {
            throw LayoutException.DuplicateName("Zone", request.name)
        }

        val overflowId = request.overflowZoneId
        if (overflowId != null) {
            zoneRepository.findById(overflowId)
                ?: throw LayoutException.InvalidReference("Zone", "Zone", overflowId)
        }

        val entity = Zone().apply {
            name = request.name
            description = request.description
            overflowZone = overflowId?.let { zoneRepository.findById(it) }
        }

        zoneRepository.persist(entity)
        return toZoneResponse(entity)
    }

    @Suppress("ThrowsCount")
    @Transactional
    fun update(id: Long, request: CreateZoneRequest): ZoneResponse {
        val entity = zoneRepository.findById(id)
            ?: throw LayoutException.NotFound("Zone", id)

        val existing = zoneRepository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("Zone", request.name)
        }

        entity.name = request.name
        entity.description = request.description
        entity.overflowZone = request.overflowZoneId?.let {
            zoneRepository.findById(it)
                ?: throw LayoutException.InvalidReference("Zone", "Zone", it)
        }

        invalidateById(id)
        return toZoneResponse(entity)
    }

    @Transactional
    fun delete(id: Long) {
        val entity = zoneRepository.findById(id)
            ?: throw LayoutException.NotFound("Zone", id)
        val locationCount = locationRepository.countByZoneId(id)
        if (locationCount > 0) {
            throw LayoutException.HasDependents("Zone", id, "locations")
        }
        invalidateById(id)
        zoneRepository.deleteById(id)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "zones")
    fun invalidateById(@CacheKey id: Long) {
        // Cache invalidation handled by annotation
    }

    fun toZoneResponse(entity: Zone): ZoneResponse = ZoneResponse(
        id = entity.id!!,
        name = entity.name,
        description = entity.description,
        overflowZoneId = entity.overflowZone?.id,
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )

    companion object {
        val SORTABLE_FIELDS = setOf("id", "name", "created")
    }
}

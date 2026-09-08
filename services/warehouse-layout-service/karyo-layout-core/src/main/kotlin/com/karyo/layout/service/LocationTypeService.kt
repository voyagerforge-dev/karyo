package com.karyo.layout.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.dto.CreateLocationTypeRequest
import com.karyo.layout.dto.LocationTypeResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.LocationTypeRepository
import com.karyo.layout.repository.StorageLocationRepository
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class LocationTypeService(
    private val locationTypeRepository: LocationTypeRepository,
    private val locationRepository: StorageLocationRepository,
) {

    fun listAll(): List<LocationTypeResponse> =
        locationTypeRepository.listAll().map { toLocationTypeResponse(it) }

    fun listAllPaginated(pagination: PaginationParams): PaginatedResponse<LocationTypeResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.ascending("name"))
        val query = locationTypeRepository.findAll(sort).page(Page.of(pagination.page, pagination.size))
        val content = query.list().map { toLocationTypeResponse(it) }
        val totalElements = query.count()
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    @CacheResult(cacheName = "location-types")
    fun findById(@CacheKey id: Long): LocationTypeResponse {
        val entity = locationTypeRepository.findById(id)
            ?: throw LayoutException.NotFound("LocationType", id)
        return toLocationTypeResponse(entity)
    }

    @Transactional
    fun create(request: CreateLocationTypeRequest): LocationTypeResponse {
        locationTypeRepository.findByName(request.name)?.let {
            throw LayoutException.DuplicateName("LocationType", request.name)
        }

        val entity = LocationType().apply {
            name = request.name
            height = request.height
            width = request.width
            depth = request.depth
            liftingCapacity = request.liftingCapacity
            fieldLiftingCapacity = request.fieldLiftingCapacity
            sectionLiftingCapacity = request.sectionLiftingCapacity
        }

        locationTypeRepository.persist(entity)
        return toLocationTypeResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: CreateLocationTypeRequest): LocationTypeResponse {
        val entity = locationTypeRepository.findById(id)
            ?: throw LayoutException.NotFound("LocationType", id)

        val existing = locationTypeRepository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("LocationType", request.name)
        }

        entity.name = request.name
        entity.height = request.height
        entity.width = request.width
        entity.depth = request.depth
        entity.liftingCapacity = request.liftingCapacity
        entity.fieldLiftingCapacity = request.fieldLiftingCapacity
        entity.sectionLiftingCapacity = request.sectionLiftingCapacity

        invalidateById(id)
        return toLocationTypeResponse(entity)
    }

    @Transactional
    fun delete(id: Long) {
        locationTypeRepository.findById(id)
            ?: throw LayoutException.NotFound("LocationType", id)
        val locationCount = locationRepository.countByLocationTypeId(id)
        if (locationCount > 0) {
            throw LayoutException.HasDependents("LocationType", id, "locations")
        }
        invalidateById(id)
        locationTypeRepository.deleteById(id)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "location-types")
    fun invalidateById(@CacheKey id: Long) {
        // Cache invalidation handled by annotation
    }

    companion object {
        val SORTABLE_FIELDS = setOf("id", "name", "created")
    }

    fun toLocationTypeResponse(entity: LocationType): LocationTypeResponse = LocationTypeResponse(
        id = entity.id!!,
        name = entity.name,
        height = entity.height,
        width = entity.width,
        depth = entity.depth,
        liftingCapacity = entity.liftingCapacity,
        fieldLiftingCapacity = entity.fieldLiftingCapacity,
        sectionLiftingCapacity = entity.sectionLiftingCapacity,
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )
}

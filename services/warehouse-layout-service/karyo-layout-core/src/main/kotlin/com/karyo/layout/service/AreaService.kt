package com.karyo.layout.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.layout.domain.model.Area
import com.karyo.layout.dto.AreaResponse
import com.karyo.layout.dto.CreateAreaRequest
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.AreaRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.vo.AreaUsage
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class AreaService(
    private val areaRepository: AreaRepository,
    private val locationRepository: StorageLocationRepository,
) {

    fun listAll(): List<AreaResponse> =
        areaRepository.listAll().map { toAreaResponse(it) }

    fun listAllPaginated(pagination: PaginationParams): PaginatedResponse<AreaResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.ascending("name"))
        val query = areaRepository.findAll(sort).page(Page.of(pagination.page, pagination.size))
        val content = query.list().map { toAreaResponse(it) }
        val totalElements = query.count()
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    @CacheResult(cacheName = "areas")
    fun findById(@CacheKey id: Long): AreaResponse {
        val entity = areaRepository.findById(id)
            ?: throw LayoutException.NotFound("Area", id)
        return toAreaResponse(entity)
    }

    @Transactional
    fun create(request: CreateAreaRequest): AreaResponse {
        areaRepository.findByName(request.name)?.let {
            throw LayoutException.DuplicateName("Area", request.name)
        }

        // Validate usages
        validateUsages(request.usages)

        val entity = Area().apply {
            name = request.name
            usages = if (request.usages.isEmpty()) null else request.usages.joinToString(",")
        }

        areaRepository.persist(entity)
        return toAreaResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: CreateAreaRequest): AreaResponse {
        val entity = areaRepository.findById(id)
            ?: throw LayoutException.NotFound("Area", id)

        val existing = areaRepository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("Area", request.name)
        }

        validateUsages(request.usages)

        entity.name = request.name
        entity.usages = if (request.usages.isEmpty()) null else request.usages.joinToString(",")

        invalidateById(id)
        return toAreaResponse(entity)
    }

    private fun validateUsages(usages: List<String>) {
        for (usage in usages) {
            try {
                AreaUsage.valueOf(usage)
            } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
                throw LayoutException.InvalidUsage(usage)
            }
        }
    }

    @Transactional
    fun delete(id: Long) {
        val entity = areaRepository.findById(id)
            ?: throw LayoutException.NotFound("Area", id)
        val locationCount = locationRepository.countByAreaId(id)
        if (locationCount > 0) {
            throw LayoutException.HasDependents("Area", id, "locations")
        }
        invalidateById(id)
        areaRepository.deleteById(id)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "areas")
    fun invalidateById(@CacheKey id: Long) {
        // Cache invalidation handled by annotation
    }

    fun toAreaResponse(entity: Area): AreaResponse = AreaResponse(
        id = entity.id!!,
        name = entity.name,
        usages = entity.usages?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )

    companion object {
        val SORTABLE_FIELDS = setOf("id", "name", "created")
    }
}

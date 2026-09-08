package com.karyo.layout.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.layout.domain.model.LocationCluster
import com.karyo.layout.dto.CreateLocationClusterRequest
import com.karyo.layout.dto.LocationClusterResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.LocationClusterRepository
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class LocationClusterService(
    private val locationClusterRepository: LocationClusterRepository,
) {

    fun listAll(): List<LocationClusterResponse> =
        locationClusterRepository.listAll().map { toClusterResponse(it) }

    fun listAllPaginated(pagination: PaginationParams): PaginatedResponse<LocationClusterResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.ascending("name"))
        val query = locationClusterRepository.findAll(sort).page(Page.of(pagination.page, pagination.size))
        val content = query.list().map { toClusterResponse(it) }
        val totalElements = query.count()
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    @CacheResult(cacheName = "clusters")
    fun findById(@CacheKey id: Long): LocationClusterResponse {
        val entity = locationClusterRepository.findById(id)
            ?: throw LayoutException.NotFound("LocationCluster", id)
        return toClusterResponse(entity)
    }

    @Transactional
    fun create(request: CreateLocationClusterRequest): LocationClusterResponse {
        locationClusterRepository.findByName(request.name)?.let {
            throw LayoutException.DuplicateName("LocationCluster", request.name)
        }

        val parent = request.parentClusterId?.let {
            locationClusterRepository.findById(it)
                ?: throw LayoutException.InvalidReference("LocationCluster", "LocationCluster", it)
        }

        val entity = LocationCluster().apply {
            name = request.name
            parentCluster = parent
        }

        locationClusterRepository.persist(entity)
        return toClusterResponse(entity)
    }

    @Suppress("ThrowsCount")
    @Transactional
    fun update(id: Long, request: CreateLocationClusterRequest): LocationClusterResponse {
        val entity = locationClusterRepository.findById(id)
            ?: throw LayoutException.NotFound("LocationCluster", id)

        val existing = locationClusterRepository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("LocationCluster", request.name)
        }

        entity.name = request.name
        entity.parentCluster = request.parentClusterId?.let {
            locationClusterRepository.findById(it)
                ?: throw LayoutException.InvalidReference("LocationCluster", "LocationCluster", it)
        }

        invalidateById(id)
        return toClusterResponse(entity)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "clusters")
    fun invalidateById(@CacheKey id: Long) {
        // Cache invalidation handled by annotation
    }

    fun toClusterResponse(entity: LocationCluster): LocationClusterResponse = LocationClusterResponse(
        id = entity.id!!,
        name = entity.name,
        parentClusterId = entity.parentCluster?.id,
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )

    companion object {
        val SORTABLE_FIELDS = setOf("id", "name", "created")
    }
}

package com.karyo.layout.service

import com.karyo.layout.domain.model.LocationCluster
import com.karyo.layout.domain.model.WorkingArea
import com.karyo.layout.dto.CreateWorkingAreaRequest
import com.karyo.layout.dto.WorkingAreaResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.LocationClusterRepository
import com.karyo.layout.repository.WorkingAreaRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * CRUD for [WorkingArea] (L5, locations-layout sprint Task 8). Mirrors [StorageAreaService]
 * almost exactly, with one deliberate difference: `delete` has NO dependents concept —
 * myWMS's `LOSWorkingArea` isn't referenced by anything else in the model (unlike
 * `StorageArea`, which strategies/item-data-areas can depend on), so delete is unconditional
 * once the row itself is found.
 */
@ApplicationScoped
class WorkingAreaService(
    private val workingAreaRepository: WorkingAreaRepository,
    private val locationClusterRepository: LocationClusterRepository,
) {

    fun listAll(): List<WorkingAreaResponse> =
        workingAreaRepository.listAll().map { toResponse(it) }

    fun findById(id: Long): WorkingAreaResponse {
        val entity = workingAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("WorkingArea", id)
        return toResponse(entity)
    }

    @Transactional
    fun create(request: CreateWorkingAreaRequest): WorkingAreaResponse {
        workingAreaRepository.findByName(request.name)?.let {
            throw LayoutException.DuplicateName("WorkingArea", request.name)
        }

        val entity = WorkingArea().apply {
            name = request.name
            clusters = resolveClusters(request.clusterIds).toMutableSet()
        }

        workingAreaRepository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: CreateWorkingAreaRequest): WorkingAreaResponse {
        val entity = workingAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("WorkingArea", id)

        val existing = workingAreaRepository.findByName(request.name)
        if (existing != null && existing.id != id) {
            throw LayoutException.DuplicateName("WorkingArea", request.name)
        }

        entity.name = request.name
        entity.clusters = resolveClusters(request.clusterIds).toMutableSet()

        return toResponse(entity)
    }

    @Transactional
    fun delete(id: Long) {
        workingAreaRepository.findById(id)
            ?: throw LayoutException.NotFound("WorkingArea", id)

        workingAreaRepository.deleteById(id)
    }

    private fun resolveClusters(clusterIds: List<Long>): List<LocationCluster> {
        val ids = clusterIds.distinct()
        if (ids.isEmpty()) return emptyList()

        val found = locationClusterRepository.list("id in ?1", ids)
        val foundIds = found.mapNotNull { it.id }.toSet()
        val missing = ids.filter { it !in foundIds }
        if (missing.isNotEmpty()) {
            throw LayoutException.InvalidReferenceList("WorkingArea", "LocationCluster", missing)
        }
        return found
    }

    private fun toResponse(entity: WorkingArea): WorkingAreaResponse = WorkingAreaResponse(
        id = entity.id!!,
        name = entity.name,
        clusterIds = entity.clusters.mapNotNull { it.id },
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )
}

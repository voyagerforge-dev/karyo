package com.karyo.layout.service

import com.karyo.layout.domain.model.TypeCapacityConstraint
import com.karyo.layout.dto.CreateTypeCapacityConstraintRequest
import com.karyo.layout.dto.TypeCapacityConstraintResponse
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.LocationTypeRepository
import com.karyo.layout.repository.TypeCapacityConstraintRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * CRUD for the `TypeCapacityConstraint` matrix (locations-layout sprint Task 4). `unitLoadTypeId`
 * is accepted **unvalidated** — see [com.karyo.layout.domain.model.TypeCapacityConstraint]'s
 * KDoc: inventory-api exposes no `UnitLoadTypeLookup` SPI today, and this sprint's brief
 * deliberately rules out inventing a new cross-module SPI just for this one check.
 * `locationTypeId` IS validated (in-module FK, [LocationTypeRepository]).
 */
@ApplicationScoped
class TypeCapacityConstraintService(
    private val repository: TypeCapacityConstraintRepository,
    private val locationTypeRepository: LocationTypeRepository,
) {

    fun listAll(locationTypeId: Long?): List<TypeCapacityConstraintResponse> =
        (if (locationTypeId != null) repository.findByLocationType(locationTypeId) else repository.listAll())
            .map { toResponse(it) }

    fun findById(id: Long): TypeCapacityConstraintResponse =
        toResponse(findEntityById(id))

    @Transactional
    fun create(request: CreateTypeCapacityConstraintRequest): TypeCapacityConstraintResponse {
        val locationType = resolveLocationType(request.locationTypeId)
        assertNotDuplicate(request.locationTypeId, request.unitLoadTypeId, excludingId = null)

        val entity = TypeCapacityConstraint().apply {
            this.locationType = locationType
            unitLoadTypeId = request.unitLoadTypeId
            allocation = request.allocation
            orderIndex = request.orderIndex
        }
        repository.persist(entity)
        return toResponse(entity)
    }

    @Transactional
    fun update(id: Long, request: CreateTypeCapacityConstraintRequest): TypeCapacityConstraintResponse {
        val entity = findEntityById(id)
        val locationType = resolveLocationType(request.locationTypeId)
        assertNotDuplicate(request.locationTypeId, request.unitLoadTypeId, excludingId = id)

        entity.locationType = locationType
        entity.unitLoadTypeId = request.unitLoadTypeId
        entity.allocation = request.allocation
        entity.orderIndex = request.orderIndex
        return toResponse(entity)
    }

    @Transactional
    fun delete(id: Long) {
        val entity = findEntityById(id)
        repository.delete(entity)
    }

    private fun findEntityById(id: Long): TypeCapacityConstraint =
        repository.findById(id) ?: throw LayoutException.NotFound("TypeCapacityConstraint", id)

    private fun resolveLocationType(locationTypeId: Long) =
        locationTypeRepository.findById(locationTypeId)
            ?: throw LayoutException.InvalidReference("TypeCapacityConstraint", "LocationType", locationTypeId)

    private fun assertNotDuplicate(locationTypeId: Long, unitLoadTypeId: Long, excludingId: Long?) {
        val existing = repository.findByPair(locationTypeId, unitLoadTypeId) ?: return
        if (existing.id != excludingId) {
            throw LayoutException.DuplicateName(
                "TypeCapacityConstraint",
                "locationType=$locationTypeId/unitLoadType=$unitLoadTypeId",
            )
        }
    }

    private fun toResponse(entity: TypeCapacityConstraint): TypeCapacityConstraintResponse = TypeCapacityConstraintResponse(
        id = entity.id!!,
        locationTypeId = entity.locationType.id!!,
        locationTypeName = entity.locationType.name,
        unitLoadTypeId = entity.unitLoadTypeId,
        allocation = entity.allocation,
        orderIndex = entity.orderIndex,
        created = entity.created.toString(),
        modified = entity.modified.toString(),
    )
}

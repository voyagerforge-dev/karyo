package com.karyo.layout.repository

import com.karyo.layout.domain.model.TypeCapacityConstraint
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TypeCapacityConstraintRepository : PanacheRepository<TypeCapacityConstraint> {
    fun findByLocationType(locationTypeId: Long): List<TypeCapacityConstraint> =
        list("locationType.id", locationTypeId)

    fun findByPair(locationTypeId: Long, unitLoadTypeId: Long): TypeCapacityConstraint? =
        find("locationType.id = ?1 and unitLoadTypeId = ?2", locationTypeId, unitLoadTypeId).firstResult()

    /**
     * Produced interface (Task 4, consumed by [com.karyo.layout.service.LocationFinderService]):
     * ALL constraint rows for a set of candidate location types, in ONE query — the finder
     * batches this once per `findPutawayLocation` call rather than querying per-candidate.
     */
    fun findByLocationTypes(locationTypeIds: Collection<Long>): List<TypeCapacityConstraint> =
        if (locationTypeIds.isEmpty()) emptyList() else list("locationType.id in ?1", locationTypeIds)
}

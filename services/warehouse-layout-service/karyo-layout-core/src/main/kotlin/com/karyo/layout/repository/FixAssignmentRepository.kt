package com.karyo.layout.repository

import com.karyo.layout.domain.model.FixAssignment
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class FixAssignmentRepository : PanacheRepository<FixAssignment> {
    fun findByLocation(locationId: Long, clientId: Long): List<FixAssignment> =
        list("location.id = ?1 and clientId = ?2", locationId, clientId)

    fun findByItemDataId(itemDataId: Long, clientId: Long): List<FixAssignment> =
        list("itemDataId = ?1 and clientId = ?2", itemDataId, clientId)

    fun findByLocationAndItem(locationId: Long, itemDataId: Long): FixAssignment? =
        find("location.id = ?1 and itemDataId = ?2", locationId, itemDataId).firstResult()

    fun findByClientId(clientId: Long): List<FixAssignment> =
        list("clientId", clientId)

    /**
     * L7 picking-facing read: fix rows for [itemDataId] whose location is one of
     * [locationIds] AND that carry a non-null [FixAssignment.maxPickAmount] — myWMS keys the
     * fix by (location, product), so this deliberately excludes rows for a different product
     * on the same location. ONE query; tenant-scoped like every other method here.
     */
    fun findPickCeilings(clientId: Long, itemDataId: Long, locationIds: Collection<Long>): List<FixAssignment> =
        list(
            "clientId = ?1 and itemDataId = ?2 and location.id in ?3 and maxPickAmount is not null",
            clientId,
            itemDataId,
            locationIds,
        )

    /**
     * R11: distinct `client_id`s owning at least one fix assignment, for the replenishment
     * scheduler's tenant loop. Deliberately unscoped (no `clientId` filter) -- same native-query
     * shape as `TenantEnumerator.activeClientIds` in karyo-monitors-core.
     */
    fun distinctClientIds(): List<Long> =
        getEntityManager()
            .createNativeQuery("SELECT DISTINCT client_id FROM karyo.fix_assignments")
            .resultList
            .map { (it as Number).toLong() }
}

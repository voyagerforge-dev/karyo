package com.karyo.layout.repository

import com.karyo.layout.domain.model.StorageArea
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class StorageAreaRepository : PanacheRepository<StorageArea> {
    fun findByName(name: String): StorageArea? = find("name", name).firstResult()

    fun findByIds(ids: List<Long>): List<StorageArea> =
        if (ids.isEmpty()) emptyList() else list("id in ?1", ids)

    /**
     * (storageAreaId, locationClusterId) pairs for the given areas, straight off the join
     * table — avoids N+1 lazy-loading each [StorageArea.clusters] collection. Backs
     * `StorageAreaService.clustersForAreas`.
     */
    @Suppress("UNCHECKED_CAST")
    fun clusterPairsForAreas(areaIds: List<Long>): List<Pair<Long, Long>> {
        if (areaIds.isEmpty()) return emptyList()
        val rows = getEntityManager()
            .createQuery("select sa.id, lc.id from StorageArea sa join sa.clusters lc where sa.id in :ids")
            .setParameter("ids", areaIds)
            .resultList as List<Array<Any>>
        return rows.map { (it[0] as Long) to (it[1] as Long) }
    }

    /**
     * PT15: true when [locationId]'s [com.karyo.layout.domain.model.StorageLocation.locationCluster]
     * is a member of any [StorageArea] with `transferStaging = true`. The subquery resolves the
     * location's cluster id (null-safe — a clusterless location's subquery result is `null`,
     * which the outer `c.id = :subquery` comparison never matches, correctly yielding zero rows
     * rather than a query error). Backs [com.karyo.layout.spi.LocationLockPort.isTransferStaging].
     */
    fun isTransferStaging(locationId: Long): Boolean {
        val count = getEntityManager()
            .createQuery(
                "select count(sa) from StorageArea sa join sa.clusters c " +
                    "where sa.transferStaging = true and c.id = (" +
                    "select l.locationCluster.id from StorageLocation l where l.id = :locationId)",
                Long::class.java,
            )
            .setParameter("locationId", locationId)
            .singleResult
        return count > 0
    }
}

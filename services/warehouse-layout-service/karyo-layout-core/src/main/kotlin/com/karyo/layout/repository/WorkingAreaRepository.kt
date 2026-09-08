package com.karyo.layout.repository

import com.karyo.layout.domain.model.WorkingArea
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class WorkingAreaRepository : PanacheRepository<WorkingArea> {
    fun findByName(name: String): WorkingArea? = find("name", name).firstResult()

    /**
     * Cluster ids assigned to [workingAreaId], straight off the join table — avoids
     * lazy-loading [WorkingArea.clusters]. Empty list for an unassigned or non-existent area
     * (mirrors [StorageAreaRepository.clusterPairsForAreas]'s style, single-area shape).
     */
    fun clusterIdsFor(workingAreaId: Long): List<Long> =
        getEntityManager()
            .createQuery("select lc.id from WorkingArea wa join wa.clusters lc where wa.id = :id", Long::class.java)
            .setParameter("id", workingAreaId)
            .resultList
}

package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

/**
 * myWMS `StorageArea` — a named SET of [LocationCluster]s (M2M). A location is "in" an
 * area iff its cluster is a member here. Distinct from the existing [Area] entity (usage
 * roles like STORAGE/PICKING) — different concept entirely, do not conflate.
 */
@Entity
@Table(name = "storage_areas")
class StorageArea : BaseEntity() {
    @Column(nullable = false, unique = true, length = 255)
    lateinit var name: String

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "storage_area_clusters",
        joinColumns = [JoinColumn(name = "storage_area_id")],
        inverseJoinColumns = [JoinColumn(name = "location_cluster_id")],
    )
    var clusters: MutableSet<LocationCluster> = mutableSetOf()

    /**
     * PT15: when true, a location in this area is a transfer-staging waypoint rather than a
     * final destination — completing a transport order there triggers
     * [com.karyo.tasks.service.ChainContinuationService] to spin up a TRANSFER successor
     * carrying the unit load on to the predecessor's real final target (see
     * [com.karyo.layout.spi.LocationLockPort.isTransferStaging]).
     */
    @Column(name = "transfer_staging", nullable = false)
    var transferStaging: Boolean = false
}

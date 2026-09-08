package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

/**
 * myWMS `LOSWorkingArea` — a named SET of [LocationCluster]s modeling an operator
 * workstation's reach (M2M, mirrors [StorageArea]'s shape exactly). A location is "in" a
 * working area iff its cluster is a member here. Distinct purpose from [StorageArea]:
 * that one restricts/hides putaway finder candidates; this one scopes which offered WORK
 * ITEMS an operator sees (see `com.karyo.layout.spi.WorkingAreaLookup`). No user binding —
 * see that SPI's KDoc.
 */
@Entity
@Table(name = "working_areas")
class WorkingArea : BaseEntity() {
    @Column(nullable = false, unique = true, length = 255)
    lateinit var name: String

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "working_area_clusters",
        joinColumns = [JoinColumn(name = "working_area_id")],
        inverseJoinColumns = [JoinColumn(name = "location_cluster_id")],
    )
    var clusters: MutableSet<LocationCluster> = mutableSetOf()
}

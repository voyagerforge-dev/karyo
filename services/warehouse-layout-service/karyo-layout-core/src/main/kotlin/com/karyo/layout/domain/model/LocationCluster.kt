package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "location_clusters")
class LocationCluster : BaseEntity() {
    @Column(nullable = false, unique = true, length = 100)
    lateinit var name: String

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_cluster_id")
    var parentCluster: LocationCluster? = null
}

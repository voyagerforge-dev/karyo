package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "zones")
class Zone : BaseEntity() {
    @Column(nullable = false, unique = true, length = 100)
    lateinit var name: String

    @Column(length = 500)
    var description: String? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "overflow_zone_id")
    var overflowZone: Zone? = null
}

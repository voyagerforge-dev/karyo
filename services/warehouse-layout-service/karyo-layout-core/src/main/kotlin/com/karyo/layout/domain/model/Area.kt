package com.karyo.layout.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "areas")
class Area : BaseEntity() {
    @Column(nullable = false, unique = true, length = 100)
    lateinit var name: String

    @Column(length = 255)
    var usages: String? = null
}

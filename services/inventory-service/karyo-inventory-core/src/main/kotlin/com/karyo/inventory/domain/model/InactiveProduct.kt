package com.karyo.inventory.domain.model

import com.karyo.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "inactive_products")
class InactiveProduct : BaseEntity() {
    @Column(name = "item_data_id", nullable = false)
    var itemDataId: Long = 0

    @Column(name = "client_id", nullable = false)
    var clientId: Long = 0

    @Column(nullable = false)
    var deactivated: Instant = Instant.now()
}

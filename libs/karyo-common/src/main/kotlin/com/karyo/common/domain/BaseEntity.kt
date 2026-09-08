package com.karyo.common.domain

import jakarta.persistence.*
import org.hibernate.annotations.Filter
import org.hibernate.annotations.FilterDef
import org.hibernate.annotations.ParamDef
import java.time.Instant

@MappedSuperclass
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Int = 0

    @Column(nullable = false, updatable = false)
    var created: Instant = Instant.now()

    @Column(nullable = false)
    var modified: Instant = Instant.now()
}

@MappedSuperclass
@FilterDef(name = "tenantFilter", parameters = [ParamDef(name = "clientId", type = Long::class)])
@Filter(name = "tenantFilter", condition = "client_id = :clientId")
abstract class TenantEntity : BaseEntity() {
    @Column(name = "client_id", nullable = false)
    var clientId: Long = 0
}

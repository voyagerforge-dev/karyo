package com.karyo.work.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "work_groups")
class WorkGroup : TenantEntity() {
    @Column(nullable = false, length = 100)
    lateinit var name: String

    /** CSV of WorkType names. */
    @Column(name = "work_types", nullable = false, length = 200)
    lateinit var workTypes: String

    /** CSV of zone names; null = all zones. */
    @Column(length = 500)
    var zones: String? = null
}

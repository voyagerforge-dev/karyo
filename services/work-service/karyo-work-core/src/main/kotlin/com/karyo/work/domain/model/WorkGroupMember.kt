package com.karyo.work.domain.model

import com.karyo.common.domain.TenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "work_group_members")
class WorkGroupMember : TenantEntity() {
    @Column(name = "work_group_id", nullable = false)
    var workGroupId: Long = 0

    @Column(name = "operator_id", nullable = false, length = 100)
    lateinit var operatorId: String
}

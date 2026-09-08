package com.karyo.work.service

import com.karyo.security.TenantContext
import com.karyo.work.domain.model.WorkGroup
import com.karyo.work.domain.model.WorkGroupMember
import com.karyo.work.repository.WorkGroupMemberRepository
import com.karyo.work.repository.WorkGroupRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

@ApplicationScoped
class WorkGroupService(
    private val groups: WorkGroupRepository,
    private val members: WorkGroupMemberRepository,
    private val tenantContext: TenantContext,
) {
    @Transactional
    fun create(name: String, workTypes: Set<String>, zones: Set<String>?): WorkGroup {
        val g = WorkGroup().apply {
            clientId = tenantContext.clientId
            this.name = name
            this.workTypes = workTypes.joinToString(",")
            this.zones = zones?.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
        groups.persist(g)
        return g
    }

    fun list(): List<WorkGroup> = groups.findByClient(tenantContext.clientId)

    @Transactional
    fun addMember(groupId: Long, operatorId: String): WorkGroupMember {
        val group = groups.findByIdAndClient(groupId, tenantContext.clientId)
            ?: throw NoSuchElementException("Work group $groupId not found")
        val existing = members.findByGroup(group.id!!, tenantContext.clientId).firstOrNull { it.operatorId == operatorId }
        if (existing != null) return existing
        val m = WorkGroupMember().apply {
            clientId = tenantContext.clientId
            workGroupId = group.id!!
            this.operatorId = operatorId
        }
        members.persist(m)
        return m
    }

    @Transactional
    fun removeMember(groupId: Long, operatorId: String) {
        groups.findByIdAndClient(groupId, tenantContext.clientId)
            ?: throw NoSuchElementException("Work group $groupId not found")
        members.deleteMember(groupId, operatorId)
    }
}

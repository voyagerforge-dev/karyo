package com.karyo.work.repository

import com.karyo.work.domain.model.WorkGroupMember
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class WorkGroupMemberRepository : PanacheRepository<WorkGroupMember> {
    fun findByOperator(clientId: Long, operatorId: String): List<WorkGroupMember> =
        list("clientId = ?1 and operatorId = ?2", clientId, operatorId)
    fun findByGroup(groupId: Long, clientId: Long): List<WorkGroupMember> =
        list("workGroupId = ?1 and clientId = ?2", groupId, clientId)
    fun deleteMember(groupId: Long, operatorId: String): Long =
        delete("workGroupId = ?1 and operatorId = ?2", groupId, operatorId)
}

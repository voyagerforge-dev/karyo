package com.karyo.work.service

import com.karyo.work.repository.WorkGroupMemberRepository
import com.karyo.work.repository.WorkGroupRepository
import com.karyo.work.spi.WorkEligibilityResolver
import com.karyo.work.vo.WorkType
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in eligibility: if the tenant has NO work groups configured, the operator is eligible for
 * ALL work-types (system open until configured). Otherwise eligibility is the union of the
 * work-types across the operator's group memberships (a non-member sees nothing).
 */
@ApplicationScoped
class DefaultWorkEligibilityResolver(
    private val groups: WorkGroupRepository,
    private val members: WorkGroupMemberRepository,
) : WorkEligibilityResolver {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = "WORK_GROUP"

    override fun resolve(operatorId: String, clientId: Long): Set<WorkType> {
        if (groups.countByClient(clientId) == 0L) return WorkType.entries.toSet()
        val myGroupIds = members.findByOperator(clientId, operatorId).map { it.workGroupId }.toSet()
        if (myGroupIds.isEmpty()) return emptySet()
        return groups.findByClient(clientId)
            .filter { it.id in myGroupIds }
            .flatMap { it.workTypes.split(",").filter(String::isNotBlank).map { t -> WorkType.valueOf(t.trim()) } }
            .toSet()
    }
}

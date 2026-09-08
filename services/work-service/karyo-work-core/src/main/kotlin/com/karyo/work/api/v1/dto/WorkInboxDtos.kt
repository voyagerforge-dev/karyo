package com.karyo.work.api.v1.dto

import com.karyo.work.dto.WorkItem

data class WorkItemResponse(
    val ref: String, val workType: String, val priority: Int, val state: String,
    val claimedBy: String?, val zone: String?, val primaryLocation: String?,
    val destination: String?, val summary: String, val createdAt: String,
    val primaryLocationId: Long? = null,
) {
    companion object {
        fun from(i: WorkItem) = WorkItemResponse(
            i.ref.token(), i.workType.name, i.priority, i.state.name, i.claimedBy,
            i.zone, i.primaryLocation, i.destination, i.summary, i.createdAt.toString(),
            i.primaryLocationId,
        )
    }
}

data class CreateWorkGroupRequest(val name: String, val workTypes: Set<String>, val zones: Set<String>? = null)
data class AddMemberRequest(val operatorId: String)
data class WorkGroupResponse(val id: Long, val name: String, val workTypes: List<String>, val zones: List<String>?)

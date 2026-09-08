package com.karyo.work.api.v1

import com.karyo.security.TenantContext
import com.karyo.work.api.v1.dto.AddMemberRequest
import com.karyo.work.api.v1.dto.CreateWorkGroupRequest
import com.karyo.work.api.v1.dto.WorkGroupResponse
import com.karyo.work.api.v1.dto.WorkItemResponse
import com.karyo.work.dto.WorkFilter
import com.karyo.work.dto.WorkRef
import com.karyo.work.service.WorkDispatchService
import com.karyo.work.service.WorkGroupService
import com.karyo.work.vo.WorkType
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/work")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class WorkInboxResource(
    private val dispatch: WorkDispatchService,
    private val groups: WorkGroupService,
    private val tenantContext: TenantContext,
) {
    private fun parseRef(ref: String): WorkRef =
        try { WorkRef.parse(ref) }
        catch (_: IllegalArgumentException) { throw BadRequestException("Invalid work ref: $ref") }

    private fun filter(type: String?, zone: String?, workingAreaId: Long? = null) = WorkFilter(
        types = type?.let {
            try { setOf(WorkType.valueOf(it)) }
            catch (_: IllegalArgumentException) { throw BadRequestException("Invalid work type: $it") }
        },
        zones = zone?.let { setOf(it) },
        workingAreaId = workingAreaId,
    )

    /**
     * Row 19 (defect-burndown-4): claim/release are writes, but the endpoint's `@RolesAllowed`
     * is static and can't vary per work type -- the annotation is broadened to the union of
     * every type's write role, and this map gates the ACTUAL required role in-body, per [WorkRef.type].
     */
    private fun requireWriteRole(type: WorkType) {
        val required = WRITE_ROLE_BY_TYPE.getValue(type)
        if (required !in tenantContext.roles) {
            throw ForbiddenException("Work type $type requires role $required")
        }
    }

    private fun validateWorkTypes(workTypes: Set<String>) {
        val invalid = workTypes.filter { name ->
            try { WorkType.valueOf(name); false } catch (_: IllegalArgumentException) { true }
        }
        if (workTypes.isEmpty() || invalid.isNotEmpty())
            throw BadRequestException("Invalid or empty workTypes: ${if (workTypes.isEmpty()) "[]" else invalid}")
    }

    /**
     * `workingAreaId` is CONTINGENT (Task 8, locations-layout sprint) — scopes the pool to
     * items whose location falls in the named working area (myWMS `LOSWorkingArea`); an
     * unknown id 400s (via [UnknownWorkingAreaException]'s mapper). Omitted = unchanged
     * behavior (regression pin).
     */
    @GET @Path("/available") @RolesAllowed("inventory-read")
    fun available(
        @QueryParam("type") type: String?,
        @QueryParam("zone") zone: String?,
        @QueryParam("workingAreaId") workingAreaId: Long?,
    ): List<WorkItemResponse> =
        dispatch.available(tenantContext.username, tenantContext.clientId, filter(type, zone, workingAreaId))
            .map(WorkItemResponse::from)

    /** No request body — @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs. */
    @POST @Path("/next") @RolesAllowed("inventory-read") @Consumes(MediaType.WILDCARD)
    fun next(@QueryParam("type") type: String?, @QueryParam("zone") zone: String?): Response {
        val item = dispatch.getNext(tenantContext.username, tenantContext.clientId, filter(type, zone))
            ?: return Response.noContent().build()
        return Response.ok(WorkItemResponse.from(item)).build()
    }

    @GET @Path("/mine") @RolesAllowed("inventory-read")
    fun mine(): List<WorkItemResponse> =
        dispatch.mine(tenantContext.username).map(WorkItemResponse::from)

    /** No request body — @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs. */
    @POST @Path("/{ref}/claim")
    @RolesAllowed("fulfillment-write", "order-write", "inventory-write", "task-write")
    @Consumes(MediaType.WILDCARD)
    fun claim(@PathParam("ref") ref: String): WorkItemResponse {
        val workRef = parseRef(ref)
        requireWriteRole(workRef.type)
        if (workRef.type !in dispatch.eligibleTypes(tenantContext.username, tenantContext.clientId))
            throw ForbiddenException("Not eligible for work type ${workRef.type}")
        return WorkItemResponse.from(dispatch.claimSpecific(workRef, tenantContext.username))
    }

    /**
     * No request body -- @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs.
     * `inventory-write` stays in the annotation's union for the [asManager] force-release read
     * below, independent of the per-type [requireWriteRole] gate.
     */
    @POST @Path("/{ref}/release")
    @RolesAllowed("fulfillment-write", "order-write", "inventory-write", "task-write")
    @Consumes(MediaType.WILDCARD)
    fun release(@PathParam("ref") ref: String): Response {
        val workRef = parseRef(ref)
        requireWriteRole(workRef.type)
        val asManager = tenantContext.roles.contains("inventory-write")
        dispatch.release(workRef, tenantContext.username, asManager)
        return Response.noContent().build()
    }

    @GET @Path("/groups") @RolesAllowed("inventory-write")
    fun listGroups(): List<WorkGroupResponse> = groups.list().map {
        WorkGroupResponse(it.id!!, it.name, it.workTypes.split(",").filter(String::isNotBlank),
            it.zones?.split(",")?.filter(String::isNotBlank))
    }

    @POST @Path("/groups") @RolesAllowed("inventory-write")
    fun createGroup(req: CreateWorkGroupRequest): Response {
        validateWorkTypes(req.workTypes)
        val g = groups.create(req.name, req.workTypes, req.zones)
        return Response.status(Response.Status.CREATED)
            .entity(WorkGroupResponse(g.id!!, g.name,
                g.workTypes.split(",").filter(String::isNotBlank),
                g.zones?.split(",")?.filter(String::isNotBlank))).build()
    }

    @POST @Path("/groups/{id}/members") @RolesAllowed("inventory-write")
    fun addMember(@PathParam("id") id: Long, req: AddMemberRequest): Response {
        groups.addMember(id, req.operatorId)
        return Response.noContent().build()
    }

    @DELETE @Path("/groups/{id}/members/{operatorId}") @RolesAllowed("inventory-write")
    fun removeMember(@PathParam("id") id: Long, @PathParam("operatorId") operatorId: String): Response {
        groups.removeMember(id, operatorId)
        return Response.noContent().build()
    }

    companion object {
        /** Row 19: PICK->fulfillment-write, RECEIVE->order-write, COUNT->inventory-write, transport types->task-write. */
        private val WRITE_ROLE_BY_TYPE: Map<WorkType, String> = mapOf(
            WorkType.PICK to "fulfillment-write",
            WorkType.RECEIVE to "order-write",
            WorkType.COUNT to "inventory-write",
            WorkType.PUTAWAY to "task-write",
            WorkType.MOVE to "task-write",
            WorkType.REPLENISH to "task-write",
            WorkType.TRANSFER to "task-write",
        )
    }
}

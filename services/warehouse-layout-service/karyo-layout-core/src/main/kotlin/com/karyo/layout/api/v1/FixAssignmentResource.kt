package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateFixAssignmentRequest
import com.karyo.layout.dto.FixAssignmentResponse
import com.karyo.layout.dto.UpdateFixAssignmentRequest
import com.karyo.layout.service.FixAssignmentService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/fix-assignments")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class FixAssignmentResource(
    private val fixAssignmentService: FixAssignmentService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listAssignments(@QueryParam("locationId") locationId: Long?): List<FixAssignmentResponse> =
        if (locationId != null) fixAssignmentService.findByLocation(locationId, tenantContext.clientId)
        else fixAssignmentService.listByClient(tenantContext.clientId)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getAssignment(@PathParam("id") id: Long): FixAssignmentResponse =
        fixAssignmentService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("layout-write")
    fun createAssignment(@Valid request: CreateFixAssignmentRequest): Response {
        val assignment = fixAssignmentService.create(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(assignment).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateAssignment(@PathParam("id") id: Long, @Valid request: UpdateFixAssignmentRequest): FixAssignmentResponse =
        fixAssignmentService.update(id, request, tenantContext.clientId)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteAssignment(@PathParam("id") id: Long): Response {
        fixAssignmentService.delete(id, tenantContext.clientId)
        return Response.noContent().build()
    }
}

package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateWorkingAreaRequest
import com.karyo.layout.dto.WorkingAreaResponse
import com.karyo.layout.service.WorkingAreaService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/working-areas")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class WorkingAreaResource(
    private val workingAreaService: WorkingAreaService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listAreas(): List<WorkingAreaResponse> = workingAreaService.listAll()

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getArea(@PathParam("id") id: Long): WorkingAreaResponse =
        workingAreaService.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun createArea(@Valid request: CreateWorkingAreaRequest): Response {
        val area = workingAreaService.create(request)
        return Response.status(Response.Status.CREATED).entity(area).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateArea(@PathParam("id") id: Long, @Valid request: CreateWorkingAreaRequest): WorkingAreaResponse =
        workingAreaService.update(id, request)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteArea(@PathParam("id") id: Long): Response {
        workingAreaService.delete(id)
        return Response.noContent().build()
    }
}

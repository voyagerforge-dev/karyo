package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.layout.dto.AreaResponse
import com.karyo.layout.dto.CreateAreaRequest
import com.karyo.layout.service.AreaService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/areas")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AreaResource(
    private val areaService: AreaService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listAreas(@BeanParam pagination: PaginationParams): PaginatedResponse<AreaResponse> =
        areaService.listAllPaginated(pagination)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getArea(@PathParam("id") id: Long): AreaResponse =
        areaService.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun createArea(@Valid request: CreateAreaRequest): Response {
        val area = areaService.create(request)
        return Response.status(Response.Status.CREATED).entity(area).build()
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteArea(@PathParam("id") id: Long): Response {
        areaService.delete(id)
        return Response.noContent().build()
    }
}

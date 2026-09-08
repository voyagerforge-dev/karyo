package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.layout.dto.CreateLocationTypeRequest
import com.karyo.layout.dto.LocationTypeResponse
import com.karyo.layout.service.LocationTypeService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/location-types")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class LocationTypeResource(
    private val locationTypeService: LocationTypeService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listLocationTypes(@BeanParam pagination: PaginationParams): PaginatedResponse<LocationTypeResponse> =
        locationTypeService.listAllPaginated(pagination)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getLocationType(@PathParam("id") id: Long): LocationTypeResponse =
        locationTypeService.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun createLocationType(@Valid request: CreateLocationTypeRequest): Response {
        val locationType = locationTypeService.create(request)
        return Response.status(Response.Status.CREATED).entity(locationType).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateLocationType(@PathParam("id") id: Long, @Valid request: CreateLocationTypeRequest): LocationTypeResponse =
        locationTypeService.update(id, request)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteLocationType(@PathParam("id") id: Long): Response {
        locationTypeService.delete(id)
        return Response.noContent().build()
    }
}

package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.layout.dto.CreateZoneRequest
import com.karyo.layout.dto.ZoneResponse
import com.karyo.layout.service.ZoneService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/zones")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ZoneResource(
    private val zoneService: ZoneService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listZones(@BeanParam pagination: PaginationParams): PaginatedResponse<ZoneResponse> =
        zoneService.listAllPaginated(pagination)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getZone(@PathParam("id") id: Long): ZoneResponse =
        zoneService.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun createZone(@Valid request: CreateZoneRequest): Response {
        val zone = zoneService.create(request)
        return Response.status(Response.Status.CREATED).entity(zone).build()
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteZone(@PathParam("id") id: Long): Response {
        zoneService.delete(id)
        return Response.noContent().build()
    }
}

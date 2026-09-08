package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateTypeCapacityConstraintRequest
import com.karyo.layout.dto.TypeCapacityConstraintResponse
import com.karyo.layout.service.TypeCapacityConstraintService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/type-capacity-constraints")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TypeCapacityConstraintResource(
    private val service: TypeCapacityConstraintService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun list(@QueryParam("locationTypeId") locationTypeId: Long?): List<TypeCapacityConstraintResponse> =
        service.listAll(locationTypeId)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun get(@PathParam("id") id: Long): TypeCapacityConstraintResponse =
        service.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun create(@Valid request: CreateTypeCapacityConstraintRequest): Response {
        val created = service.create(request)
        return Response.status(Response.Status.CREATED).entity(created).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun update(@PathParam("id") id: Long, @Valid request: CreateTypeCapacityConstraintRequest): TypeCapacityConstraintResponse =
        service.update(id, request)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun delete(@PathParam("id") id: Long): Response {
        service.delete(id)
        return Response.noContent().build()
    }
}

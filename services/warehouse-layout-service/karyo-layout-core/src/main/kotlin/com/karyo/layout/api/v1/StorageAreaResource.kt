package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateStorageAreaRequest
import com.karyo.layout.dto.StorageAreaResponse
import com.karyo.layout.service.StorageAreaService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/storage-areas")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StorageAreaResource(
    private val storageAreaService: StorageAreaService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listAreas(): List<StorageAreaResponse> = storageAreaService.listAll()

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getArea(@PathParam("id") id: Long): StorageAreaResponse =
        storageAreaService.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun createArea(@Valid request: CreateStorageAreaRequest): Response {
        val area = storageAreaService.create(request)
        return Response.status(Response.Status.CREATED).entity(area).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateArea(@PathParam("id") id: Long, @Valid request: CreateStorageAreaRequest): StorageAreaResponse =
        storageAreaService.update(id, request)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteArea(@PathParam("id") id: Long): Response {
        storageAreaService.delete(id)
        return Response.noContent().build()
    }
}

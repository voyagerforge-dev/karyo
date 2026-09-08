package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateItemDataAreaRequest
import com.karyo.layout.dto.ItemDataAreaResponse
import com.karyo.layout.dto.UpdateItemDataAreaRequest
import com.karyo.layout.service.ItemDataAreaService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/item-data-areas")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ItemDataAreaResource(
    private val itemDataAreaService: ItemDataAreaService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listAssignments(@QueryParam("storageAreaId") storageAreaId: Long?): List<ItemDataAreaResponse> =
        if (storageAreaId != null) itemDataAreaService.findByArea(storageAreaId, tenantContext.clientId)
        else itemDataAreaService.listByClient(tenantContext.clientId)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getAssignment(@PathParam("id") id: Long): ItemDataAreaResponse =
        itemDataAreaService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("layout-write")
    fun createAssignment(@Valid request: CreateItemDataAreaRequest): Response {
        val entity = itemDataAreaService.create(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(entity).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateAssignment(@PathParam("id") id: Long, @Valid request: UpdateItemDataAreaRequest): ItemDataAreaResponse =
        itemDataAreaService.update(id, request, tenantContext.clientId)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteAssignment(@PathParam("id") id: Long): Response {
        itemDataAreaService.delete(id, tenantContext.clientId)
        return Response.noContent().build()
    }
}

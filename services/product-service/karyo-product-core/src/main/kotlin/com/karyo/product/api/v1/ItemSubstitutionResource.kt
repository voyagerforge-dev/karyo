package com.karyo.product.api.v1

import com.karyo.product.dto.CreateItemSubstitutionRequest
import com.karyo.product.dto.ItemSubstitutionResponse
import com.karyo.product.service.ItemSubstitutionService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/item-substitutions")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ItemSubstitutionResource(
    private val service: ItemSubstitutionService,
    private val tenantContext: TenantContext,
) {
    @GET
    @RolesAllowed("product-read")
    fun list(@QueryParam("itemDataId") itemDataId: Long): List<ItemSubstitutionResponse> =
        service.listByPrimary(itemDataId, tenantContext.clientId)

    @POST
    @RolesAllowed("product-write")
    fun create(@Valid request: CreateItemSubstitutionRequest): Response =
        Response.status(Response.Status.CREATED).entity(service.create(request, tenantContext.clientId)).build()

    @DELETE
    @Path("/{id}")
    @RolesAllowed("product-write")
    fun delete(@PathParam("id") id: Long): Response {
        service.delete(id, tenantContext.clientId)
        return Response.noContent().build()
    }
}

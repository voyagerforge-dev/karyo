package com.karyo.product.api.v1

import com.karyo.product.dto.CreateItemUnitRequest
import com.karyo.product.dto.ItemUnitResponse
import com.karyo.product.service.ItemUnitService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/item-units")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ItemUnitResource(
    private val itemUnitService: ItemUnitService,
) {

    @GET
    @RolesAllowed("product-read")
    fun listUnits(): List<ItemUnitResponse> = itemUnitService.listAll()

    @POST
    @RolesAllowed("product-write")
    fun createUnit(@Valid request: CreateItemUnitRequest): Response {
        val unit = itemUnitService.createUnit(request)
        return Response.status(Response.Status.CREATED).entity(unit).build()
    }
}

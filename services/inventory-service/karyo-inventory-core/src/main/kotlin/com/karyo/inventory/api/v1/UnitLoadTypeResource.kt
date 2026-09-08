package com.karyo.inventory.api.v1

import com.karyo.inventory.api.dto.CreateUnitLoadTypeRequest
import com.karyo.inventory.api.dto.UnitLoadTypeResponse
import com.karyo.inventory.api.dto.UpdateUnitLoadTypeRequest
import com.karyo.inventory.domain.model.UnitLoadType
import com.karyo.inventory.service.UnitLoadTypeService
import jakarta.annotation.security.RolesAllowed
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/unit-load-types")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class UnitLoadTypeResource(
    private val service: UnitLoadTypeService,
) {
    @GET
    @RolesAllowed("inventory-read")
    fun listAll() = service.findAll().map { mapToResponse(it) }

    @GET
    @Path("/{id}")
    @RolesAllowed("inventory-read")
    fun getById(@PathParam("id") id: Long) = mapToResponse(service.findById(id))

    @POST
    @RolesAllowed("inventory-write")
    fun create(request: CreateUnitLoadTypeRequest): Response {
        val ult = service.create(request)
        return Response.status(201).entity(mapToResponse(ult)).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("inventory-write")
    fun update(@PathParam("id") id: Long, request: UpdateUnitLoadTypeRequest): UnitLoadTypeResponse =
        mapToResponse(service.update(id, request))

    @DELETE
    @Path("/{id}")
    @RolesAllowed("inventory-write")
    fun delete(@PathParam("id") id: Long): Response {
        service.delete(id)
        return Response.noContent().build()
    }

    private fun mapToResponse(ult: UnitLoadType) = UnitLoadTypeResponse(
        id = ult.id!!,
        name = ult.name,
        usages = ult.usages,
        aggregateStocks = ult.aggregateStocks,
        height = ult.height,
        width = ult.width,
        depth = ult.depth,
        liftingCapacity = ult.liftingCapacity,
        weight = ult.weight,
        manageEmpties = ult.manageEmpties,
        created = ult.created,
        modified = ult.modified,
    )
}

package com.karyo.layout.api.v1

import com.karyo.layout.dto.CreateStorageStrategyRequest
import com.karyo.layout.dto.StorageStrategyResponse
import com.karyo.layout.dto.StrategyAreaResponse
import com.karyo.layout.service.StorageStrategyService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/storage-strategies")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StorageStrategyResource(
    private val strategyService: StorageStrategyService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listStrategies(): List<StorageStrategyResponse> =
        strategyService.listByClient(tenantContext.clientId)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getStrategy(@PathParam("id") id: Long): StorageStrategyResponse =
        strategyService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("layout-write")
    fun createStrategy(@Valid request: CreateStorageStrategyRequest): Response {
        val strategy = strategyService.create(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(strategy).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateStrategy(
        @PathParam("id") id: Long,
        @Valid request: CreateStorageStrategyRequest,
    ): StorageStrategyResponse =
        strategyService.update(id, request, tenantContext.clientId)

    @GET
    @Path("/{id}/areas")
    @RolesAllowed("layout-read")
    fun getStrategyAreas(@PathParam("id") id: Long): List<StrategyAreaResponse> =
        strategyService.getAreas(id, tenantContext.clientId)

    @PUT
    @Path("/{id}/areas")
    @RolesAllowed("layout-write")
    fun setStrategyAreas(@PathParam("id") id: Long, areaIds: List<Long>): List<StrategyAreaResponse> =
        strategyService.setAreas(id, areaIds, tenantContext.clientId)
}

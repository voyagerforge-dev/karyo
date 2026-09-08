package com.karyo.replenishment.api.v1

import com.karyo.replenishment.dto.ReplenishmentNeed
import com.karyo.replenishment.dto.ReplenishmentScanResult
import com.karyo.replenishment.service.ReplenishmentService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/replenishment")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ReplenishmentResource(
    private val replenishmentService: ReplenishmentService,
    private val tenantContext: TenantContext,
) {
    @POST
    @Path("/scan")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun scan(): ReplenishmentScanResult = replenishmentService.scan(tenantContext.clientId)

    @GET
    @Path("/needs")
    @RolesAllowed("task-read")
    fun needs(): List<ReplenishmentNeed> = replenishmentService.needs(tenantContext.clientId)
}

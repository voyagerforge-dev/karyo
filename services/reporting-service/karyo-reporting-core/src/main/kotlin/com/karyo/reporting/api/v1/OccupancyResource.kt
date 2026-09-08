package com.karyo.reporting.api.v1

import com.karyo.reporting.api.v1.dto.OccupancyResponse
import com.karyo.reporting.service.OccupancyService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/v1/insights/occupancy")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("inventory-read")
class OccupancyResource(
    private val service: OccupancyService,
    private val tenant: TenantContext,
) {
    @GET
    fun occupancy(): OccupancyResponse = service.build(tenant.clientId)
}

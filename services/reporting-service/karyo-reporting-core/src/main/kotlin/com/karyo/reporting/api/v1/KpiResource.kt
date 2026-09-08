package com.karyo.reporting.api.v1

import com.karyo.reporting.api.v1.dto.KpiDashboardResponse
import com.karyo.reporting.service.KpiDashboardService
import com.karyo.reporting.service.KpiRange
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import java.time.Instant

@Path("/api/v1/insights/kpis")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("inventory-read")
class KpiResource(
    private val service: KpiDashboardService,
    private val tenant: TenantContext,
) {
    @GET
    fun kpis(@QueryParam("range") @DefaultValue("30D") rangeCode: String): KpiDashboardResponse {
        val range = KpiRange.from(rangeCode) ?: throw BadRequestException("Invalid range: $rangeCode")
        return service.build(tenant.clientId, range, Instant.now())
    }
}

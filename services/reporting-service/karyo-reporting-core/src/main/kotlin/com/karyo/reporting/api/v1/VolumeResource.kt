package com.karyo.reporting.api.v1

import com.karyo.reporting.api.v1.dto.CategoryVolumeResponse
import com.karyo.reporting.service.CategoryVolumeService
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

/** Phase B (B18): pick volume grouped by product category (`item_data.trade_group`). */
@Path("/api/v1/insights/volume-by-category")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("inventory-read")
class VolumeResource(
    private val service: CategoryVolumeService,
    private val tenant: TenantContext,
) {
    @GET
    fun volumeByCategory(@QueryParam("range") @DefaultValue("30D") rangeCode: String): List<CategoryVolumeResponse> {
        val range = KpiRange.from(rangeCode) ?: throw BadRequestException("Invalid range: $rangeCode")
        return service.build(tenant.clientId, range, Instant.now())
    }
}

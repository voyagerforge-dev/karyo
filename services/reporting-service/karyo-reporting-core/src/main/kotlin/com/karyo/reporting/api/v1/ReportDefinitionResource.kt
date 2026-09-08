package com.karyo.reporting.api.v1

import com.karyo.reporting.dto.CreateReportDefinitionRequest
import com.karyo.reporting.dto.ReportDefinitionResponse
import com.karyo.reporting.service.ReportDefinitionService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Phase B (B19): tenant-scoped saved-reports store. */
@Path("/api/v1/report-definitions")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ReportDefinitionResource(
    private val service: ReportDefinitionService,
    private val tenant: TenantContext,
) {
    @GET
    @RolesAllowed("report-read")
    fun list(): List<ReportDefinitionResponse> = service.list(tenant.clientId)

    @POST
    @RolesAllowed("report-write")
    fun create(@Valid request: CreateReportDefinitionRequest): Response =
        Response.status(Response.Status.CREATED).entity(service.create(request, tenant.clientId)).build()

    @DELETE
    @Path("/{id}")
    @RolesAllowed("report-write")
    fun delete(@PathParam("id") id: Long): Response {
        service.delete(id, tenant.clientId)
        return Response.noContent().build()
    }
}

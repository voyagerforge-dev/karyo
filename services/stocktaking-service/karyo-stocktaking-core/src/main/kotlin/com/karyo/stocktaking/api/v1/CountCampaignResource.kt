package com.karyo.stocktaking.api.v1

import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountCampaignRollupView
import com.karyo.stocktaking.dto.CountCampaignView
import com.karyo.stocktaking.dto.CreateCampaignRequest
import com.karyo.stocktaking.service.CountCampaignService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * REST surface for [com.karyo.stocktaking.domain.model.CountCampaign] (St1) -- a real lifecycle
 * above [com.karyo.stocktaking.domain.model.CountSession], deliberately exceeding legacy myWMS's
 * write-only campaign grouping (see the entity KDoc).
 *
 * RBAC: reuses `inventory-read` / `inventory-write`, same as [StocktakingResource] -- campaign
 * management is a stocktaking operation, no new Keycloak role needed.
 */
@Path("/api/v1/count-campaigns")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class CountCampaignResource(
    private val service: CountCampaignService,
    private val tenantContext: TenantContext,
) {

    @POST
    @RolesAllowed("inventory-write")
    fun create(@Valid req: CreateCampaignRequest): Response =
        Response.status(Response.Status.CREATED)
            .entity(service.createCampaign(req, tenantContext.clientId))
            .build()

    @GET
    @RolesAllowed("inventory-read")
    fun list(): List<CountCampaignView> = service.listCampaigns(tenantContext.clientId)

    @GET
    @Path("/{id}")
    @RolesAllowed("inventory-read")
    fun get(@PathParam("id") id: Long): CountCampaignRollupView =
        service.getCampaign(id, tenantContext.clientId)

    /** No request body -- @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs. */
    @POST
    @Path("/{id}/close")
    @RolesAllowed("inventory-write")
    @Consumes(MediaType.WILDCARD)
    fun close(@PathParam("id") id: Long): CountCampaignView =
        service.closeCampaign(id, tenantContext.clientId)
}

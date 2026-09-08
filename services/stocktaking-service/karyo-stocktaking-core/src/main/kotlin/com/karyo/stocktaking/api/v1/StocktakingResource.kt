package com.karyo.stocktaking.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.security.TenantContext
import com.karyo.stocktaking.dto.CountEntryView
import com.karyo.stocktaking.dto.CountOrderView
import com.karyo.stocktaking.dto.CountSessionSummaryView
import com.karyo.stocktaking.dto.CountSessionView
import com.karyo.stocktaking.dto.StartCountRequest
import com.karyo.stocktaking.dto.SubmitCountRequest
import com.karyo.stocktaking.dto.UnitLoadMissingRequest
import com.karyo.stocktaking.service.StocktakingService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * REST surface for the cycle-count / stocktaking domain.
 *
 * RBAC: reuses existing `inventory-read` / `inventory-write` realm roles — counting is an
 * inventory operation. No new Keycloak roles are needed (avoids the --reset-db gotcha).
 *
 * Blind-count note: GET /count-orders/{id}?view=entry returns a [CountEntryView] that omits
 * plannedAmount and countedAmount so operators cannot see expected quantities before entering
 * their count. The full review view (with amounts) is returned when view is absent or any
 * other value.
 */
@Path("/api/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class StocktakingResource(
    private val service: StocktakingService,
    private val tenantContext: TenantContext,
) {

    @POST
    @Path("/count-sessions")
    @RolesAllowed("inventory-write")
    fun start(@Valid req: StartCountRequest): Response =
        Response.status(Response.Status.CREATED)
            .entity(service.startCount(req, tenantContext.clientId))
            .build()

    @GET
    @Path("/count-sessions")
    @RolesAllowed("inventory-read")
    fun sessions(@BeanParam pagination: PaginationParams): PaginatedResponse<CountSessionSummaryView> =
        service.listSessions(tenantContext.clientId, pagination.page, pagination.size)

    @GET
    @Path("/count-sessions/{id}")
    @RolesAllowed("inventory-read")
    fun session(@PathParam("id") id: Long): CountSessionView =
        service.getSession(id, tenantContext.clientId)

    /**
     * Returns the count order. When [view] == "entry" the response is a blind [CountEntryView]
     * (no amounts); otherwise returns the full [CountOrderView] for manager review.
     */
    @GET
    @Path("/count-orders/{id}")
    @RolesAllowed("inventory-read")
    fun order(@PathParam("id") id: Long, @QueryParam("view") view: String?): Any =
        if (view == "entry") {
            service.entryView(id, tenantContext.clientId)
        } else {
            service.orderView(id, tenantContext.clientId)
        }

    @POST
    @Path("/count-orders/{id}/count")
    @RolesAllowed("inventory-write")
    fun count(@PathParam("id") id: Long, @Valid req: SubmitCountRequest): CountOrderView =
        service.submitCount(id, req.lines, tenantContext.clientId)

    /**
     * Reports a whole unit load missing from the location (St4) — zeroes every still-PLANNED
     * line for that unit load on this order. Order state is unchanged (see
     * [com.karyo.stocktaking.service.StocktakingService.unitLoadMissing]). 404 if the unit load
     * has no line on this order; 409 unless the order is GENERATED.
     */
    @POST
    @Path("/count-orders/{id}/unit-loads/missing")
    @RolesAllowed("inventory-write")
    fun unitLoadMissing(@PathParam("id") id: Long, @Valid req: UnitLoadMissingRequest): CountEntryView =
        service.unitLoadMissing(id, req.unitLoadId, tenantContext.clientId)

    /** No request body — @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs. */
    @POST
    @Path("/count-orders/{id}/accept")
    @RolesAllowed("inventory-write")
    @Consumes(MediaType.WILDCARD)
    fun accept(@PathParam("id") id: Long): CountOrderView =
        service.accept(id, tenantContext.clientId)

    /** No request body — @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs. */
    @POST
    @Path("/count-orders/{id}/recount")
    @RolesAllowed("inventory-write")
    @Consumes(MediaType.WILDCARD)
    fun recount(@PathParam("id") id: Long): CountOrderView =
        service.recount(id, tenantContext.clientId)

    /**
     * Location-level drop — cancels the order in place, no replacement order. Allowed from
     * GENERATED(50) or COUNTED(500); a FINISHED or already-CANCELLED order 409s.
     * No request body — @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs.
     */
    @POST
    @Path("/count-orders/{id}/cancel")
    @RolesAllowed("inventory-write")
    @Consumes(MediaType.WILDCARD)
    fun cancel(@PathParam("id") id: Long): CountOrderView =
        service.cancelOrder(id, tenantContext.clientId)

    /**
     * Confirms a location is empty (St3) — the sanctioned way to close out a zero-line order
     * (FINISHES directly) or zero every still-open line on an order that does have lines
     * (COUNTED, for manager review). Requires GENERATED(50); 409 otherwise. No request body —
     * @Consumes(WILDCARD) avoids a 415-before-auth for empty POSTs. See
     * [com.karyo.stocktaking.service.StocktakingService.locationEmpty] for the branch KDoc.
     */
    @POST
    @Path("/count-orders/{id}/location-empty")
    @RolesAllowed("inventory-write")
    @Consumes(MediaType.WILDCARD)
    fun locationEmpty(@PathParam("id") id: Long): CountOrderView =
        service.locationEmpty(id, tenantContext.clientId)
}

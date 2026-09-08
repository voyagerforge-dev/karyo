package com.karyo.orders.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.orders.dto.AsnFinishResponse
import com.karyo.orders.dto.AsnResponse
import com.karyo.orders.dto.CreateAsnRequest
import com.karyo.orders.dto.CreateUlAdviceRequest
import com.karyo.orders.dto.UpdateAsnRequest
import com.karyo.orders.service.AsnDocumentService
import com.karyo.orders.service.AsnService
import com.karyo.orders.service.AsnUlAdviceService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * ASN endpoints. Guarded by the order-read/order-write roles for v1.2 — dedicated
 * receiving roles (e.g. RECEIVER-scoped) may split out later when the role model
 * is refined.
 */
@Path("/api/v1/asns")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class AsnResource(
    private val asnService: AsnService,
    private val ulAdviceService: AsnUlAdviceService,
    private val asnDocumentService: AsnDocumentService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("order-read")
    fun listAsns(
        @BeanParam pagination: PaginationParams,
        @QueryParam("state") state: Int?,
        @QueryParam("q") q: String?,
    ): PaginatedResponse<AsnResponse> =
        asnService.list(tenantContext.clientId, pagination, state, q)

    @GET
    @Path("/{id}")
    @RolesAllowed("order-read")
    fun getAsn(@PathParam("id") id: Long): AsnResponse =
        asnService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("order-write")
    fun createAsn(@Valid request: CreateAsnRequest): Response {
        val asn = asnService.create(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(asn).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("order-write")
    fun updateAsn(@PathParam("id") id: Long, @Valid request: UpdateAsnRequest): AsnResponse =
        asnService.update(id, request, tenantContext.clientId)

    // Action endpoints take no request body — accept any (or no) Content-Type.

    @POST
    @Path("/{id}/release")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun releaseAsn(@PathParam("id") id: Long): AsnResponse =
        asnService.release(id, tenantContext.clientId)

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun cancelAsn(@PathParam("id") id: Long): AsnResponse =
        asnService.cancel(id, tenantContext.clientId)

    @POST
    @Path("/{id}/finish")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun finishAsn(@PathParam("id") id: Long): AsnFinishResponse =
        asnService.finish(id, tenantContext.clientId)

    // ── UL pre-advice (Karyo-native, see AsnUlAdvice's KDoc) ────────────

    @POST
    @Path("/{id}/ul-advices")
    @RolesAllowed("order-write")
    fun createUlAdvice(@PathParam("id") id: Long, @Valid request: CreateUlAdviceRequest): Response {
        val advice = ulAdviceService.create(id, request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(advice).build()
    }

    @DELETE
    @Path("/{id}/ul-advices/{adviceId}")
    @RolesAllowed("order-write")
    fun deleteUlAdvice(@PathParam("id") id: Long, @PathParam("adviceId") adviceId: Long): Response {
        ulAdviceService.delete(id, adviceId, tenantContext.clientId)
        return Response.noContent().build()
    }

    // ZPL pre-print sheet: one ^XA...^XZ block per advice. @Transactional mirrors
    // UnitLoadResource.label -- not strictly needed here (no lazy relation is read
    // past the render call) but kept for parity with the documents-sprint precedent.
    @GET
    @Path("/{id}/ul-labels.zpl")
    @Produces("text/plain")
    @RolesAllowed("order-read")
    @Transactional
    fun ulLabels(
        @PathParam("id") id: Long,
        @QueryParam("store") @DefaultValue("false") store: Boolean,
    ): String = asnDocumentService.ulLabelsZpl(id, tenantContext.clientId, store)
}

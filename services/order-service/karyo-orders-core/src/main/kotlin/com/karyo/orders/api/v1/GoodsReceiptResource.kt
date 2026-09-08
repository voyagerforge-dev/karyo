package com.karyo.orders.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.orders.dto.AttachAsnRequest
import com.karyo.orders.dto.CreateGoodsReceiptRequest
import com.karyo.orders.dto.GoodsReceiptResponse
import com.karyo.orders.dto.ReceiveLineRequest
import com.karyo.orders.dto.ReceiveLineResponse
import com.karyo.orders.dto.UpdateGoodsReceiptRequest
import com.karyo.orders.service.GoodsReceiptService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
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
 * Goods-receipt endpoints. Guarded by the order-read/order-write roles for v1.2 —
 * dedicated receiving roles (e.g. RECEIVER-scoped) may split out later when the
 * role model is refined.
 */
@Path("/api/v1/goods-receipts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class GoodsReceiptResource(
    private val receiptService: GoodsReceiptService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("order-read")
    fun listReceipts(
        @BeanParam pagination: PaginationParams,
        @QueryParam("state") state: Int?,
        @QueryParam("asnId") asnId: Long?,
    ): PaginatedResponse<GoodsReceiptResponse> =
        receiptService.list(tenantContext.clientId, pagination, state, asnId)

    @GET
    @Path("/{id}")
    @RolesAllowed("order-read")
    fun getReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("order-write")
    fun createReceipt(@Valid request: CreateGoodsReceiptRequest): Response {
        val receipt = receiptService.create(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(receipt).build()
    }

    /** The receive-line action: creates INCOMING stock and records the line. */
    @POST
    @Path("/{id}/lines")
    @RolesAllowed("order-write")
    fun receiveLine(@PathParam("id") id: Long, @Valid request: ReceiveLineRequest): Response {
        val result = receiptService.receiveLine(id, request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(result).build()
    }

    /** V424 M2M: binds one more ASN, re-running the create-time guards (RETOUR 422, ASN state 409, tenant match). */
    @POST
    @Path("/{id}/asns")
    @RolesAllowed("order-write")
    fun attachAsn(@PathParam("id") id: Long, @Valid request: AttachAsnRequest): GoodsReceiptResponse =
        receiptService.attachAsn(id, request.asnId, tenantContext.clientId)

    /** V424 M2M: unbinds an ASN — 409 while a non-reversed line still references one of its lines. */
    @DELETE
    @Path("/{id}/asns/{asnId}")
    @RolesAllowed("order-write")
    fun detachAsn(@PathParam("id") id: Long, @PathParam("asnId") asnId: Long): Response {
        receiptService.detachAsn(id, asnId, tenantContext.clientId)
        return Response.noContent().build()
    }

    /** B7: header-scalar update (prio/receiptDate/dock); receiptType stays immutable. */
    @PUT
    @Path("/{id}")
    @RolesAllowed("order-write")
    fun updateReceipt(@PathParam("id") id: Long, @Valid request: UpdateGoodsReceiptRequest): GoodsReceiptResponse =
        receiptService.update(id, request, tenantContext.clientId)

    // Action endpoints take no request body — accept any (or no) Content-Type.

    /** B7: claim for the calling operator (pure metadata — state never moves). */
    @POST
    @Path("/{id}/claim")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun claimReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.claim(id, tenantContext.username, tenantContext.clientId)

    /**
     * B7: release the claim. asManager = the caller holds MANAGER (same mechanism as
     * WorkInboxResource.release deriving its manager override from the token roles) —
     * a manager may release ANOTHER operator's claim; a non-owner without it gets 409.
     */
    @POST
    @Path("/{id}/release")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun releaseReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.release(
            id,
            tenantContext.username,
            asManager = tenantContext.roles.contains("MANAGER"),
            clientId = tenantContext.clientId,
        )

    /** B7: orthogonal pause — stamps pausedAt; receiveLine/finish refuse 409 while paused. */
    @POST
    @Path("/{id}/pause")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun pauseReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.pause(id, tenantContext.clientId)

    /** B7: clears the pause stamp — the receipt resumes exactly where it was. */
    @POST
    @Path("/{id}/resume")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun resumeReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.resume(id, tenantContext.clientId)

    @POST
    @Path("/{id}/finish")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun finishReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.finish(id, tenantContext.clientId)

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun cancelReceipt(@PathParam("id") id: Long): GoodsReceiptResponse =
        receiptService.cancel(id, tenantContext.clientId)

    /**
     * B3 removeGoodsReceiptLineWithStocks: total undo of a received line — soft-
     * reverses it, soft-deletes its stock, decrements the bound ASN line, and
     * cancels its pending putaway task (same transaction).
     */
    @DELETE
    @Path("/{id}/lines/{lineId}")
    @RolesAllowed("order-write")
    fun reverseLine(@PathParam("id") id: Long, @PathParam("lineId") lineId: Long): GoodsReceiptResponse =
        receiptService.reverseLine(id, lineId, tenantContext.clientId)
}

package com.karyo.fulfillment.api.v1

import com.karyo.documents.CsvWriter
import com.karyo.fulfillment.api.v1.dto.BulkConfirmRequest
import com.karyo.fulfillment.api.v1.dto.BulkConfirmResponse
import com.karyo.fulfillment.api.v1.dto.BulkLineResponse
import com.karyo.fulfillment.api.v1.dto.ConfirmPickRequest
import com.karyo.fulfillment.api.v1.dto.ExtinguishRequest
import com.karyo.fulfillment.api.v1.dto.PickOrderResponse
import com.karyo.fulfillment.api.v1.dto.PickResponse
import com.karyo.fulfillment.api.v1.dto.ReleaseToPickingRequest
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.service.BulkPickService
import com.karyo.fulfillment.service.ExtinguishService
import com.karyo.fulfillment.service.PickLifecycleService
import com.karyo.fulfillment.service.PickOrderService
import com.karyo.fulfillment.service.PickTopUpService
import com.karyo.product.spi.ProductLookup
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class PickOrderResource(
    private val service: PickOrderService,
    private val lifecycleService: PickLifecycleService,
    private val topUpService: PickTopUpService,
    private val extinguishService: ExtinguishService,
    private val productLookup: ProductLookup,
    private val tenantContext: TenantContext,
    private val bulkPickService: BulkPickService,
) {
    /**
     * Row 8: [PickOrderService.releaseToPicking] now returns a list (`createTypeOrders` can split
     * one release into several PickOrders), so this endpoint returns a JSON ARRAY in every case --
     * including the pre-row-8 single-PickOrder case -- rather than sometimes an object and
     * sometimes an array. Breaking response-shape change; see the register row 8 release note.
     */
    @POST
    @Path("/pick-orders")
    @RolesAllowed("fulfillment-write")
    fun release(@Valid request: ReleaseToPickingRequest): Response {
        val pickOrders = service.releaseToPicking(request.deliveryOrderId, request.targetUnitLoadTypeId)
        val body = pickOrders.map { toResponse(it, service.picksOf(it.id!!)) }
        return Response.status(Response.Status.CREATED).entity(body).build()
    }

    // Row 19: ONE ProductLookup.findMeasuresByIds call across the WHOLE page (not one per
    // order) -- collect every pick's itemDataId first, batch-fetch, then hand the same map to
    // every PickOrderResponse.from call. See PickWeightVolumeCalculator's KDoc.
    //
    // Row :1470 (A8): autoOpenPending is hardcoded false here, deliberately NOT computed per
    // row -- see PickOrderResponse.autoOpenPending's KDoc for why batching it would cost 2
    // queries PER ORDER on this page with no batch SPI to fold them into one. Callers needing
    // the real signal read the detail endpoint below.
    @GET
    @Path("/pick-orders")
    @RolesAllowed("fulfillment-read")
    fun list(): List<PickOrderResponse> {
        val orders = service.listPickOrders()
        val picksByOrder = orders.associate { it.id!! to service.picksOf(it.id!!) }
        // Deliberately the AMBIENT single-arg overload, not the explicit-`clientId` one
        // (burndown-6 final-review ruling). This is a REST-only display read: the principal's own
        // readScope is the correct universe for it, and an OPS principal legitimately sees shared
        // client-0 catalog products. Strict owner equality would blank out their weight/volume.
        val measures = productLookup.findMeasuresByIds(picksByOrder.values.flatten().map { it.itemDataId }.toSet())
        return orders.map { PickOrderResponse.from(it, picksByOrder.getValue(it.id!!), measures, autoOpenPending = false) }
    }

    /**
     * Single-get path: batches this one order's picks into a single
     * [ProductLookup.findMeasuresByIds] call, and computes the real (non-hardcoded)
     * [PickOrderResponse.autoOpenPending] via [PickOrderService.isAutoOpenPending] -- see that
     * method's KDoc and the field's own KDoc for why this is proportionate here but not on
     * `list()`.
     */
    private fun toResponse(po: PickOrder, picks: List<Pick>): PickOrderResponse {
        // Ambient overload for the same reason as `list()` above: REST-only display read, the
        // principal's readScope is the right universe, and a strict-owner overload would hide the
        // shared client-0 catalog rows an OPS principal may legitimately see.
        val measures = productLookup.findMeasuresByIds(picks.map { it.itemDataId }.toSet())
        return PickOrderResponse.from(po, picks, measures, service.isAutoOpenPending(po))
    }

    // D12: CSV export -- SAME role as list, delegating to the identical `PickOrderService
    // .listPickOrders()` (already `clientId`-scoped via `PickOrderRepository.findByClient`, never
    // a hand-rolled query) so tenant scoping is inherited rather than re-implemented. list()
    // returns raw entities (not the DTO), so prio/operatorId/created -- absent from
    // PickOrderResponse -- are read straight off PickOrder/BaseEntity. Declared before
    // `/pick-orders/{id}`: a literal segment must be matched ahead of the `{id}` template, or
    // "export.csv" gets captured as an id and fails Long conversion.
    @GET
    @Path("/pick-orders/export.csv")
    @Produces("text/csv")
    @RolesAllowed("fulfillment-read")
    fun exportCsv(): ByteArray {
        val all = service.listPickOrders()
        val capped = all.take(CsvWriter.EXPORT_MAX_ROWS)
        val rows = capped.map { po ->
            listOf(
                // Row 20: an EXTINGUISH order has no backing DeliveryOrder — "—" rather than a
                // fabricated reference.
                po.pickOrderNumber, po.deliveryOrderNumber ?: "—", po.state.toString(), po.prio.toString(),
                po.operatorId, service.picksOf(po.id!!).size.toString(), po.created.toString(),
            )
        }
        return CsvWriter.write(headers = EXPORT_HEADERS, rows = rows, totalElements = all.size.toLong())
    }

    /**
     * Sprint B: SKU-aggregated view of a bulk PickOrder's still-open picks -- 409 for a non-bulk
     * order (see [BulkPickService.bulkLines]). Declared before `/pick-orders/{id}` for the same
     * literal-segment discipline as `export.csv` above, though the extra `/bulk-lines` segment
     * already makes the two routes unambiguous either way.
     */
    @GET
    @Path("/pick-orders/{id}/bulk-lines")
    @RolesAllowed("fulfillment-read")
    fun bulkLines(@PathParam("id") id: Long): List<BulkLineResponse> =
        bulkPickService.bulkLines(id, tenantContext.clientId)

    /**
     * Sprint B: fan-out confirm for one source stock unit of a bulk PickOrder (see
     * [BulkPickService.bulkConfirm] for the allocation-order share split and its 422 guard
     * cases). Declared before `/pick-orders/{id}`, same discipline as [bulkLines] above.
     */
    @POST
    @Path("/pick-orders/{id}/bulk-confirm")
    @RolesAllowed("fulfillment-write")
    fun bulkConfirm(@PathParam("id") id: Long, @Valid request: BulkConfirmRequest): BulkConfirmResponse =
        bulkPickService.bulkConfirm(id, request, tenantContext.clientId)

    @GET
    @Path("/pick-orders/{id}")
    @RolesAllowed("fulfillment-read")
    fun get(@PathParam("id") id: Long): PickOrderResponse {
        val po = service.getPickOrder(id)
        return toResponse(po, service.picksOf(po.id!!))
    }

    /**
     * Per-slice confirm. Row :2051 (defect-burndown-6): refused with a 409 when the pick belongs
     * to a BULK pick order -- those slices are only ever confirmed through the fan-out at
     * `POST /pick-orders/{id}/bulk-confirm` (see [BulkPickService.requireNotOnBulkOrder]). The
     * guard runs BEFORE [PickOrderService.confirmPick] so a refused call never touches stock.
     */
    @POST
    @Path("/picks/{id}/confirm")
    @RolesAllowed("fulfillment-write")
    fun confirm(@PathParam("id") id: Long, @Valid request: ConfirmPickRequest): PickResponse {
        bulkPickService.requireNotOnBulkOrder(id, tenantContext.clientId)
        return PickResponse.from(service.confirmPick(id, request.pickedAmount, request.targetUnitLoadId))
    }

    /**
     * Order-level force-finish cancel (row 14). asManager derives from the MANAGER role exactly
     * like [com.karyo.work.api.v1.WorkInboxResource]'s release override — a non-owner without it
     * gets a 409 from [PickLifecycleService.cancelOrder], never a 403 (RBAC-missing-role is the
     * only 403 case on this endpoint, via `@RolesAllowed`).
     */
    @POST
    @Path("/pick-orders/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun cancelOrder(@PathParam("id") id: Long): PickOrderResponse {
        val po = lifecycleService.cancelOrder(id, tenantContext.username, tenantContext.roles.contains("MANAGER"))
        return toResponse(po, service.picksOf(po.id!!))
    }

    /**
     * Per-line cancel (row 14) — myWMS `cancelPick` semantics; see [PickLifecycleService.cancelPick]
     * KDoc. M6 fix: asManager derives from the MANAGER role exactly like [cancelOrder] above — a
     * non-owner without it gets a 409 from the service, never a 403.
     */
    @POST
    @Path("/pick-orders/{orderId}/picks/{pickId}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun cancelPick(@PathParam("orderId") orderId: Long, @PathParam("pickId") pickId: Long): PickResponse =
        PickResponse.from(
            lifecycleService.cancelPick(orderId, pickId, tenantContext.username, tenantContext.roles.contains("MANAGER")),
        )

    /**
     * Top-up (row 15) — see [PickTopUpService.topUp] KDoc for the "covered" derivation and the
     * 409 cases (nothing to add; order state outside `[RELEASED, PICKED)`).
     */
    @POST
    @Path("/pick-orders/{id}/add-picks")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun addPicks(@PathParam("id") id: Long): PickOrderResponse {
        val po = topUpService.topUp(id)
        return toResponse(po, service.picksOf(po.id!!))
    }

    /**
     * Extinguish (stock-clearance) picks (row 20) — see [ExtinguishService.extinguish] KDoc for
     * the selector/reservation/merge rules. Declared before `/pick-orders/{id}` matters only for
     * GET (this is POST, so there's no method-level clash either way), but the literal-segment
     * discipline is kept consistent with `export.csv`'s placement above.
     *
     * M7 fix: a body-less POST deserializes to a `null` entity (no JSON to parse), and a Kotlin
     * non-null parameter type does NOT protect against that at the JAX-RS/reflection boundary —
     * before this fix, `request.stockUnitIds` below NPE'd to a 500. [request] is therefore typed
     * nullable and checked explicitly, mirroring [ExtinguishService.requireExactlyOneSelector]'s
     * own `BadRequestException` (400) idiom for "malformed extinguish input" rather than inventing
     * a second one.
     */
    @POST
    @Path("/pick-orders/extinguish")
    @RolesAllowed("fulfillment-write")
    fun extinguish(request: ExtinguishRequest?): Response {
        val body = request ?: throw BadRequestException("request body is required")
        val po = extinguishService.extinguish(body.stockUnitIds, body.unitLoadId, body.targetUnitLoadTypeId)
        val responseBody = toResponse(po, service.picksOf(po.id!!))
        return Response.status(Response.Status.CREATED).entity(responseBody).build()
    }

    private companion object {
        val EXPORT_HEADERS = listOf(
            "pickOrderNumber", "deliveryOrderNumber", "state", "prio", "operatorId", "picksCount", "created",
        )
    }
}

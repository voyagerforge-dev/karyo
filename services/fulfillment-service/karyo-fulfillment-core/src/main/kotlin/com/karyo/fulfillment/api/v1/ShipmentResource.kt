package com.karyo.fulfillment.api.v1

import com.karyo.fulfillment.api.v1.dto.AddAdHocUnitRequest
import com.karyo.fulfillment.api.v1.dto.ManifestRequestDto
import com.karyo.fulfillment.api.v1.dto.OpenPackingRequest
import com.karyo.fulfillment.api.v1.dto.PackRequest
import com.karyo.fulfillment.api.v1.dto.ShipmentOrderRef
import com.karyo.fulfillment.api.v1.dto.ShipmentResponse
import com.karyo.fulfillment.api.v1.dto.ShippingUnitLineResponse
import com.karyo.fulfillment.api.v1.dto.ShippingUnitResponse
import com.karyo.fulfillment.domain.model.Shipment
import com.karyo.fulfillment.repository.ShipmentOrderRepository
import com.karyo.fulfillment.repository.ShippingUnitRepository
import com.karyo.fulfillment.service.AdHocShippingUnitService
import com.karyo.fulfillment.service.PackingService
import com.karyo.fulfillment.service.ShippingLifecycleService
import com.karyo.fulfillment.service.ShippingService
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

@Path("/api/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ShipmentResource(
    private val service: PackingService,
    private val shippingService: ShippingService,
    private val lifecycleService: ShippingLifecycleService,
    private val adHocShippingUnitService: AdHocShippingUnitService,
    private val shipmentOrderRepository: ShipmentOrderRepository,
    private val shippingUnitRepository: ShippingUnitRepository,
    private val tenantContext: TenantContext,
) {
    @POST
    @Path("/shipments")
    @RolesAllowed("fulfillment-write")
    fun open(@Valid request: OpenPackingRequest): Response {
        val shipment = service.openPacking(request.deliveryOrderId)
        val body = toDetailResponse(shipment)
        return Response.status(Response.Status.CREATED).entity(body).build()
    }

    @POST
    @Path("/shipments/{id}/pack")
    @RolesAllowed("fulfillment-write")
    fun pack(@PathParam("id") id: Long, @Valid request: PackRequest): ShipmentResponse {
        val shipment = service.pack(id, request.weight, request.type)
        return toDetailResponse(shipment)
    }

    /**
     * S5: attaches an ad-hoc ON_STOCK unit load to the shipment as a new shipping unit -- no pick
     * order behind it. 404 if the unit load isn't found for this tenant; 409 for every integrity
     * refusal (not entirely ON_STOCK, DELETABLE, already on a live shipment, or the shipment
     * itself past PACKED).
     */
    @POST
    @Path("/shipments/{id}/shipping-units")
    @RolesAllowed("fulfillment-write")
    fun addAdHocUnit(@PathParam("id") id: Long, @Valid request: AddAdHocUnitRequest): Response {
        val shipment = adHocShippingUnitService.addAdHocUnit(id, request.unitLoadId)
        val body = toDetailResponse(shipment)
        return Response.status(Response.Status.CREATED).entity(body).build()
    }

    @POST
    @Path("/shipments/{id}/manifest")
    @RolesAllowed("fulfillment-write")
    fun manifest(@PathParam("id") id: Long, @Valid request: ManifestRequestDto): ShipmentResponse {
        val s = shippingService.manifest(id, request.carrierName, request.carrierService, request.trackingNumber)
        return toDetailResponse(s)
    }

    @POST
    @Path("/shipments/{id}/dispatch")
    @RolesAllowed("fulfillment-write")
    fun dispatch(@PathParam("id") id: Long): ShipmentResponse {
        val s = shippingService.dispatch(id)
        return toDetailResponse(s)
    }

    /**
     * Fix (task-1 review, Important 2): a page's GROUP shipments resolve their `orders` here too
     * (Task 6's shipments-list UI renders `deliveryOrderNumber ?? "${orders.length} orders"` and
     * searches `orders[].number`) -- batched with ONE [ShipmentOrderRepository.findByShipmentIds]
     * call over just this page's group-shipment ids, never a per-row query.
     */
    @GET
    @Path("/shipments")
    @RolesAllowed("fulfillment-read")
    fun list(): List<ShipmentResponse> {
        val shipments = service.listShipments()
        val clientId = tenantContext.clientId
        val groupShipmentIds = shipments.filter { it.isGroup }.mapNotNull { it.id }
        val ordersByShipmentId = shipmentOrderRepository.findByShipmentIds(groupShipmentIds, clientId)
            .groupBy({ it.shipmentId }, { ShipmentOrderRef(it.deliveryOrderId, it.deliveryOrderNumber) })
        return shipments.map { s ->
            ShipmentResponse.from(s, service.unitsOf(s.id!!), ordersByShipmentId[s.id].orEmpty())
        }
    }

    /**
     * Detail route: unlike every other route on this resource, [ShippingUnitResponse.lines]
     * is populated here (one [ShippingUnitRepository.findLinesByUnitIds] call), never on [list].
     */
    @GET
    @Path("/shipments/{id}")
    @RolesAllowed("fulfillment-read")
    fun get(@PathParam("id") id: Long): ShipmentResponse {
        val shipment = service.getShipment(id)
        val units = service.unitsOf(shipment.id!!)
        val linesByUnit = shippingUnitRepository.findLinesByUnitIds(units.mapNotNull { it.id }, tenantContext.clientId)
            .groupBy { it.shippingUnitId }
        val unitResponses = units.map { u ->
            ShippingUnitResponse.from(
                u,
                linesByUnit[u.id].orEmpty().map { l ->
                    ShippingUnitLineResponse(l.id!!, l.itemDataNumber, l.lotNumber, l.amount, l.deliveryOrderId)
                },
            )
        }
        return ShipmentResponse(
            shipment.id!!, shipment.shipmentNumber, shipment.deliveryOrderId, shipment.deliveryOrderNumber, shipment.state,
            shipment.carrierName, shipment.carrierService, shipment.trackingNumber,
            unitResponses, shipment.operatorId, shipment.pausedAt?.toString(),
            shipment.consolidationGroupId, shipment.waveId, ordersOf(shipment),
        )
    }

    // Action endpoints take no request body -- accept any (or no) Content-Type.

    /** S3: claim for the calling operator (pure metadata -- state never moves). */
    @POST
    @Path("/shipments/{id}/claim")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun claim(@PathParam("id") id: Long): ShipmentResponse {
        val shipment = lifecycleService.claim(id, tenantContext.username)
        return toDetailResponse(shipment)
    }

    /**
     * S3: release the claim. asManager derives from the MANAGER role exactly like
     * [PickOrderResource.cancelOrder] -- a non-owner without it gets a 409 from
     * [ShippingLifecycleService.release], never a 403.
     */
    @POST
    @Path("/shipments/{id}/release")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun release(@PathParam("id") id: Long): ShipmentResponse {
        val shipment = lifecycleService.release(id, tenantContext.username, tenantContext.roles.contains("MANAGER"))
        return toDetailResponse(shipment)
    }

    /** S3: orthogonal pause -- stamps pausedAt; pack/manifest/dispatch refuse 409 while paused. */
    @POST
    @Path("/shipments/{id}/pause")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun pause(@PathParam("id") id: Long): ShipmentResponse {
        val shipment = lifecycleService.pause(id)
        return toDetailResponse(shipment)
    }

    /** S3: clears the pause stamp -- the shipment resumes exactly where it was. */
    @POST
    @Path("/shipments/{id}/resume")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun resume(@PathParam("id") id: Long): ShipmentResponse {
        val shipment = lifecycleService.resume(id)
        return toDetailResponse(shipment)
    }

    /**
     * S4: cancels a pre-manifest shipment, restoring every unit's stock per origin. 409
     * `shipment-not-cancelable` once the shipment has passed PACKED.
     */
    @POST
    @Path("/shipments/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("fulfillment-write")
    fun cancel(@PathParam("id") id: Long): ShipmentResponse {
        val shipment = lifecycleService.cancel(id)
        return toDetailResponse(shipment)
    }

    /** S4: removes one shipping unit, restoring its unit-load stock per origin. */
    @DELETE
    @Path("/shipments/{id}/shipping-units/{unitId}")
    @RolesAllowed("fulfillment-write")
    fun removeUnit(@PathParam("id") id: Long, @PathParam("unitId") unitId: Long): ShipmentResponse {
        val shipment = lifecycleService.removeUnit(id, unitId)
        return toDetailResponse(shipment)
    }

    /** S4: removes one shipping-unit line only -- no stock change (restoration is unit-granularity). */
    @DELETE
    @Path("/shipments/{id}/shipping-units/{unitId}/lines/{lineId}")
    @RolesAllowed("fulfillment-write")
    fun removeLine(
        @PathParam("id") id: Long,
        @PathParam("unitId") unitId: Long,
        @PathParam("lineId") lineId: Long,
    ): ShipmentResponse {
        val shipment = lifecycleService.removeLine(id, unitId, lineId)
        return toDetailResponse(shipment)
    }

    /** Every single-shipment route (all but [list]) resolves member orders for a GROUP shipment; per-order shipments stay `orders: []`. */
    private fun toDetailResponse(shipment: Shipment): ShipmentResponse =
        ShipmentResponse.from(shipment, service.unitsOf(shipment.id!!), ordersOf(shipment))

    private fun ordersOf(shipment: Shipment): List<ShipmentOrderRef> =
        if (shipment.isGroup) {
            shipmentOrderRepository.findByShipmentId(shipment.id!!, tenantContext.clientId)
                .map { ShipmentOrderRef(it.deliveryOrderId, it.deliveryOrderNumber) }
        } else {
            emptyList()
        }
}

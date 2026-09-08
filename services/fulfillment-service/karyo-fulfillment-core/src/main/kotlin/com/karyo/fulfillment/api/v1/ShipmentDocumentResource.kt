package com.karyo.fulfillment.api.v1

import com.karyo.fulfillment.service.ShipmentDocumentService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam

/**
 * On-demand shipping-document downloads — bill of lading (PDF), packing slip (PDF), carton
 * label (ZPL), packet content list (PDF, D8) and shipment packet list (PDF, D9). Gated
 * documents are refused (409 document-not-ready) until the shipment reaches their gating
 * state via [ShipmentDocumentService]; the content list carries no gate. Read endpoints,
 * so `fulfillment-read`. `?store=true` opt-in archives the rendered bytes via `DocumentStore`
 * (Task 2, docstore-templates sprint) — a no-op when the docstore module isn't wired.
 */
@Path("/api/v1")
@ApplicationScoped
class ShipmentDocumentResource(private val service: ShipmentDocumentService) {

    @GET
    @Path("/shipments/{id}/bol.pdf")
    @Produces("application/pdf")
    @RolesAllowed("fulfillment-read")
    fun bol(@PathParam("id") id: Long, @QueryParam("store") @DefaultValue("false") store: Boolean): ByteArray =
        service.bolPdf(id, store)

    // Sprint C, Task 5: ?orderId= narrows a group shipment's slip to one member's section.
    @GET
    @Path("/shipments/{id}/packing-slip.pdf")
    @Produces("application/pdf")
    @RolesAllowed("fulfillment-read")
    fun packingSlip(
        @PathParam("id") id: Long,
        @QueryParam("store") @DefaultValue("false") store: Boolean,
        @QueryParam("orderId") orderId: Long?,
    ): ByteArray = service.packingSlipPdf(id, store, orderId)

    @GET
    @Path("/shipping-units/{id}/label.zpl")
    @Produces("text/plain")
    @RolesAllowed("fulfillment-read")
    fun label(@PathParam("id") id: Long, @QueryParam("store") @DefaultValue("false") store: Boolean): String =
        service.labelZpl(id, store)

    // D8: packet content list — no availability gate, see ShipmentDocumentService KDoc.
    @GET
    @Path("/shipping-units/{id}/content-list.pdf")
    @Produces("application/pdf")
    @RolesAllowed("fulfillment-read")
    fun contentList(@PathParam("id") id: Long, @QueryParam("store") @DefaultValue("false") store: Boolean): ByteArray =
        service.packetContentListPdf(id, store)

    // D9: shipment packet list — gated same as packing-slip (PACKED/650), see ShipmentDocumentService KDoc.
    @GET
    @Path("/shipments/{id}/packet-list.pdf")
    @Produces("application/pdf")
    @RolesAllowed("fulfillment-read")
    fun packetList(@PathParam("id") id: Long, @QueryParam("store") @DefaultValue("false") store: Boolean): ByteArray =
        service.packetListPdf(id, store)
}

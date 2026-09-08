package com.karyo.fulfillment.api.v1

import com.karyo.fulfillment.service.PickDocumentService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam

/**
 * On-demand pick ticket (PDF) download for a pick order — see [PickDocumentService] for why it
 * carries no availability gate (unlike [ShipmentDocumentResource]'s BOL/label). Split out from
 * [PickOrderResource] to keep the JSON CRUD resource and document downloads separate, mirroring
 * the [ShipmentResource]/[ShipmentDocumentResource] split. Read endpoint, so `fulfillment-read`.
 * `?store=true` opt-in archives via `DocumentStore` (Task 2, docstore-templates sprint).
 */
@Path("/api/v1")
@ApplicationScoped
class PickDocumentResource(private val service: PickDocumentService) {

    @GET
    @Path("/pick-orders/{id}/pick-ticket.pdf")
    @Produces("application/pdf")
    @RolesAllowed("fulfillment-read")
    fun pickTicket(@PathParam("id") id: Long, @QueryParam("store") @DefaultValue("false") store: Boolean): ByteArray =
        service.pickTicketPdf(id, store)
}

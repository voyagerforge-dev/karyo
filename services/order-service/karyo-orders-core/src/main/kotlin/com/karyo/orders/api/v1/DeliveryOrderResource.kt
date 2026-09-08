package com.karyo.orders.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.documents.CsvWriter
import com.karyo.orders.dto.CreateDeliveryOrderRequest
import com.karyo.orders.dto.DeliveryOrderReleaseResponse
import com.karyo.orders.dto.DeliveryOrderResponse
import com.karyo.orders.dto.UpdateDeliveryOrderRequest
import com.karyo.orders.service.OrderDocumentService
import com.karyo.orders.service.OrderService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.Consumes
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

@Path("/api/v1/delivery-orders")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class DeliveryOrderResource(
    private val orderService: OrderService,
    private val documentService: OrderDocumentService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("order-read")
    fun listOrders(
        @BeanParam pagination: PaginationParams,
        @QueryParam("state") state: Int?,
        @QueryParam("q") q: String?,
    ): PaginatedResponse<DeliveryOrderResponse> =
        orderService.list(tenantContext.clientId, pagination, state, q)

    // D12: CSV export -- SAME role + SAME filters as listOrders, delegating to the identical
    // scoped OrderService.list call (never a hand-rolled query) so tenant scoping is inherited
    // rather than re-implemented. Declared before `/{id}`: a literal segment must be matched
    // ahead of the `{id}` template (see StockUnitResource's `/amount` for the same discipline),
    // or "export.csv" gets captured as an id and fails Long conversion.
    @GET
    @Path("/export.csv")
    @Produces("text/csv")
    @RolesAllowed("order-read")
    fun exportCsv(
        @QueryParam("state") state: Int?,
        @QueryParam("q") q: String?,
    ): ByteArray {
        val pagination = PaginationParams().apply { size = CsvWriter.EXPORT_MAX_ROWS }
        val page = orderService.list(tenantContext.clientId, pagination, state, q)
        val rows = page.content.map { o ->
            listOf(
                o.orderNumber, o.stateName, o.externalNumber, o.customerName,
                o.city, o.country, o.deliveryDate?.toString(), o.lines.size.toString(), o.created,
            )
        }
        return CsvWriter.write(headers = EXPORT_HEADERS, rows = rows, totalElements = page.page.totalElements)
    }

    @GET
    @Path("/{id}")
    @RolesAllowed("order-read")
    fun getOrder(@PathParam("id") id: Long): DeliveryOrderResponse =
        orderService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("order-write")
    fun createOrder(@Valid request: CreateDeliveryOrderRequest): Response {
        val order = orderService.create(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(order).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("order-write")
    fun updateOrder(@PathParam("id") id: Long, @Valid request: UpdateDeliveryOrderRequest): DeliveryOrderResponse =
        orderService.update(id, request, tenantContext.clientId)

    // Action endpoints take no request body — accept any (or no) Content-Type.

    @POST
    @Path("/{id}/release")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun releaseOrder(@PathParam("id") id: Long): DeliveryOrderReleaseResponse =
        orderService.release(id, tenantContext.clientId)

    @POST
    @Path("/{id}/retry-reservation")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun retryReservation(@PathParam("id") id: Long): DeliveryOrderReleaseResponse =
        orderService.retryReservation(id, tenantContext.clientId)

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun cancelOrder(@PathParam("id") id: Long): DeliveryOrderResponse =
        orderService.cancel(id, tenantContext.clientId)

    /** Row 10: claim for the calling operator (pure metadata -- state never moves). */
    @POST
    @Path("/{id}/claim")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun claimOrder(@PathParam("id") id: Long): DeliveryOrderResponse =
        orderService.claim(id, tenantContext.username, tenantContext.clientId)

    /**
     * Row 10: release the claim. NOT `/release` -- that path already means release-to-picking, a
     * different business operation. asManager = the caller holds MANAGER (same mechanism as
     * [releaseOrder]/`WorkInboxResource.release` deriving its manager override from the token
     * roles) -- a manager may release ANOTHER operator's claim; a non-owner without it gets 409.
     */
    @POST
    @Path("/{id}/release-operator")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("order-write")
    fun releaseOperator(@PathParam("id") id: Long): DeliveryOrderResponse =
        orderService.releaseOperator(
            id,
            tenantContext.username,
            asManager = tenantContext.roles.contains("MANAGER"),
            clientId = tenantContext.clientId,
        )

    // D7: delivery note PDF — ordered-vs-picked reconciliation, gated on PICKED+. `?store=true`
    // opt-in archives via DocumentStore (Task 2, docstore-templates sprint).

    @GET
    @Path("/{id}/delivery-note.pdf")
    @Produces("application/pdf")
    @RolesAllowed("order-read")
    fun deliveryNote(
        @PathParam("id") id: Long,
        @QueryParam("store") @DefaultValue("false") store: Boolean,
    ): ByteArray = documentService.deliveryNotePdf(id, store)

    companion object {
        private val EXPORT_HEADERS = listOf(
            "orderNumber", "stateName", "externalNumber", "customerName",
            "city", "country", "deliveryDate", "lineCount", "created",
        )
    }
}

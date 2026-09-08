package com.karyo.tasks.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.security.TenantContext
import com.karyo.tasks.dto.CompleteTransportOrderRequest
import com.karyo.tasks.dto.CreateTransportOrderRequest
import com.karyo.tasks.dto.TransportOrderResponse
import com.karyo.tasks.exception.TaskException
import com.karyo.tasks.service.TaskService
import com.karyo.tasks.vo.TransportType
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
 * Transport-order (putaway, move, replenish & transfer) REST surface.
 *
 * RBAC: uses the realm's `task-read` / `task-write` roles (defined in
 * `infrastructure/keycloak/karyo-realm-prod.json`). PUTAWAY/REPLENISH/TRANSFER tasks are
 * all auto-created (receiving, the replenishment engine, and chain continuation
 * respectively), never via this API — POST creates manual MOVE tasks only.
 */
@Path("/api/v1/transport-orders")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class TransportOrderResource(
    private val taskService: TaskService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("task-read")
    fun list(
        @BeanParam pagination: PaginationParams,
        @QueryParam("state") state: Int?,
        @QueryParam("type") type: String?,
        @QueryParam("operatorId") operatorId: String?,
        @QueryParam("q") q: String?,
        @QueryParam("paused") paused: Boolean?,
    ): PaginatedResponse<TransportOrderResponse> =
        taskService.list(tenantContext.clientId, pagination, state, parseType(type), operatorId, q, paused)

    @GET
    @Path("/{id}")
    @RolesAllowed("task-read")
    fun get(@PathParam("id") id: Long): TransportOrderResponse =
        taskService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("task-write")
    fun createManualMove(@Valid request: CreateTransportOrderRequest): Response {
        val order = taskService.createManualMove(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(order).build()
    }

    /**
     * Assigns the task to the CALLER: the operator is derived from the JWT
     * (`tenantContext.username`), mirroring `WorkInboxResource` -- a client-supplied name is
     * never trusted for audit attribution (WORKLIST defect row, 2026-08-19). The method declares
     * no entity parameter, so any request body (the old `{"operatorId": ...}` shape included) is
     * accepted and ignored; `@Consumes(WILDCARD)` also admits body-less POSTs (an entity
     * parameter here would 415 a POST with no Content-Type).
     */
    @POST
    @Path("/{id}/assign")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun assign(@PathParam("id") id: Long): TransportOrderResponse =
        taskService.assign(id, tenantContext.username, tenantContext.clientId)

    @POST
    @Path("/{id}/start")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun start(@PathParam("id") id: Long): TransportOrderResponse =
        taskService.start(id, tenantContext.clientId)

    @POST
    @Path("/{id}/complete")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun complete(@PathParam("id") id: Long, @Valid request: CompleteTransportOrderRequest?): TransportOrderResponse =
        taskService.complete(id, request ?: CompleteTransportOrderRequest(), tenantContext.clientId)

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun cancel(@PathParam("id") id: Long): TransportOrderResponse =
        taskService.cancel(id, tenantContext.clientId)

    /** PT18: orthogonal pause — stamps pausedAt; assign/start/complete refuse 409 while paused. */
    @POST
    @Path("/{id}/pause")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun pause(@PathParam("id") id: Long): TransportOrderResponse =
        taskService.pause(id, tenantContext.clientId)

    /** PT18: clears the pause stamp — the task resumes exactly where it was. */
    @POST
    @Path("/{id}/resume")
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed("task-write")
    fun resume(@PathParam("id") id: Long): TransportOrderResponse =
        taskService.resume(id, tenantContext.clientId)

    private fun parseType(type: String?): TransportType? =
        type?.takeIf { it.isNotBlank() }?.let {
            runCatching { TransportType.valueOf(it.uppercase()) }
                .getOrElse { throw TaskException.ValidationFailed("invalid transport type: $type") }
        }
}

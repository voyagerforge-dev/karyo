package com.karyo.webhooks.api.v1

import com.karyo.common.exception.ProblemDetail
import com.karyo.security.TenantContext
import com.karyo.webhooks.api.v1.dto.CreatedSubscriptionResponse
import com.karyo.webhooks.api.v1.dto.CreateSubscriptionRequest
import com.karyo.webhooks.api.v1.dto.SubscriptionResponse
import com.karyo.webhooks.api.v1.dto.UpdateSubscriptionRequest
import com.karyo.webhooks.domain.model.WebhookSubscription
import com.karyo.webhooks.service.WebhookSubscriptionService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PATCH
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo

@Path("/api/v1/webhook-subscriptions")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("integration-admin")
class WebhookSubscriptionResource(
    private val service: WebhookSubscriptionService,
    private val tenant: TenantContext,
) {
    @Context
    lateinit var uriInfo: UriInfo

    private fun toResponse(s: WebhookSubscription) =
        SubscriptionResponse(
            id = s.id!!,
            name = s.name,
            targetUrl = s.targetUrl,
            eventTypes = s.eventTypes,
            active = s.active,
        )

    @POST
    fun create(req: CreateSubscriptionRequest): Response {
        // SYS (client 0) owns no goods — nothing to subscribe to. Refusing at registration is
        // the real gate; WebhookFanoutScheduler additionally guards clientId=0 rows in case one
        // exists anyway (e.g. seeded directly). Returned as an RFC 7807 ProblemDetail body (not
        // a raw JAX-RS exception) so the response matches every other module's error contract —
        // a bare BadRequestException falls through to RESTEasy's default (non-JSON) error body,
        // which the frontend's ApiError parsing can't read, surfacing only a generic
        // "Server returned 400" toast instead of this message.
        if (tenant.clientId == 0L) {
            val problem = ProblemDetail(
                type = "https://karyo.com/errors/sys-client-forbidden",
                title = "Sys Client Forbidden",
                status = 400,
                detail = "client 0 (SYS) has no goods to subscribe to",
                instance = uriInfo.requestUri.path,
            )
            return Response.status(400).entity(problem).build()
        }
        val s = service.create(tenant.clientId, req.name, req.targetUrl, req.eventTypes, req.active)
        return Response.status(201)
            .entity(
                CreatedSubscriptionResponse(
                    id = s.id!!,
                    name = s.name,
                    targetUrl = s.targetUrl,
                    eventTypes = s.eventTypes,
                    active = s.active,
                    secret = s.secret,
                )
            )
            .build()
    }

    @GET
    fun list(): List<SubscriptionResponse> =
        service.list(tenant.clientId).map(::toResponse)

    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: Long): SubscriptionResponse =
        toResponse(service.get(tenant.clientId, id))

    @PATCH
    @Path("/{id}")
    fun update(@PathParam("id") id: Long, req: UpdateSubscriptionRequest): SubscriptionResponse =
        toResponse(
            service.update(
                clientId = tenant.clientId,
                id = id,
                name = req.name,
                url = req.targetUrl,
                eventTypes = req.eventTypes,
                active = req.active,
            )
        )

    @DELETE
    @Path("/{id}")
    fun delete(@PathParam("id") id: Long): Response {
        service.delete(tenant.clientId, id)
        return Response.noContent().build()
    }

    /** Enqueues a synthetic ping delivery; the scheduler will attempt it on the next cycle. */
    @POST
    @Path("/{id}/test")
    @Consumes(MediaType.WILDCARD)
    fun test(@PathParam("id") id: Long): Response {
        service.ping(tenant.clientId, id)
        return Response.status(202).build()
    }
}

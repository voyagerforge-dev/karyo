package com.karyo.webhooks.api.v1

import com.karyo.security.TenantContext
import com.karyo.webhooks.api.v1.dto.DeliveryResponse
import com.karyo.webhooks.domain.model.WebhookDelivery
import com.karyo.webhooks.service.WebhookDeliveryService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/webhook-deliveries")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("integration-admin")
class WebhookDeliveryResource(
    private val service: WebhookDeliveryService,
    private val tenant: TenantContext,
) {
    private fun resp(d: WebhookDelivery) = DeliveryResponse(
        id = d.id!!,
        subscriptionId = d.subscriptionId,
        eventType = d.eventType,
        status = d.status.name,
        attempts = d.attempts,
        lastResponseCode = d.lastResponseCode,
        lastError = d.lastError,
        nextAttemptAt = d.nextAttemptAt.toString(),
        deliveredAt = d.deliveredAt?.toString(),
        created = d.created.toString(),
    )

    @GET
    fun list(
        @QueryParam("subscriptionId") subscriptionId: Long?,
        @QueryParam("status") status: String?,
        @QueryParam("limit") @DefaultValue("100") limit: Int,
    ): List<DeliveryResponse> =
        service.list(tenant.clientId, subscriptionId, status, limit).map(::resp)

    @POST
    @Path("/{id}/redeliver")
    @Consumes(MediaType.WILDCARD)
    fun redeliver(@PathParam("id") id: Long): Response {
        service.redeliver(tenant.clientId, id)
        return Response.status(202).build()
    }
}

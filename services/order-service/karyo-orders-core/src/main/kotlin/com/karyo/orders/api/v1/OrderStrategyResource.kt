package com.karyo.orders.api.v1

import com.karyo.orders.dto.CreateOrderStrategyRequest
import com.karyo.orders.dto.OrderStrategyResponse
import com.karyo.orders.dto.UpdateOrderStrategyRequest
import com.karyo.orders.service.OrderStrategyService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/order-strategies")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class OrderStrategyResource(
    private val strategyService: OrderStrategyService,
) {

    @GET
    @RolesAllowed("order-read")
    fun listStrategies(): List<OrderStrategyResponse> =
        strategyService.list()

    @GET
    @Path("/{id}")
    @RolesAllowed("order-read")
    fun getStrategy(@PathParam("id") id: Long): OrderStrategyResponse =
        strategyService.findById(id)

    @POST
    @RolesAllowed("order-write")
    fun createStrategy(@Valid request: CreateOrderStrategyRequest): Response {
        val strategy = strategyService.create(request)
        return Response.status(Response.Status.CREATED).entity(strategy).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("order-write")
    fun updateStrategy(@PathParam("id") id: Long, @Valid request: UpdateOrderStrategyRequest): OrderStrategyResponse =
        strategyService.update(id, request)
}

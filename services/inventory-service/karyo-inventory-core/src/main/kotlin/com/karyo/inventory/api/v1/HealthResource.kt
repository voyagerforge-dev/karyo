package com.karyo.inventory.api.v1

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
class HealthResource {

    @GET
    @Path("/ping")
    fun ping(): Map<String, String> = mapOf(
        "service" to "inventory-service",
        "status" to "ok",
    )
}

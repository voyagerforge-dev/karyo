package com.karyo.auth.api.v1

import com.karyo.auth.dto.ClientConsistencyReport
import com.karyo.auth.dto.ClientResponse
import com.karyo.auth.dto.CreateClientRequest
import com.karyo.auth.dto.UpdateClientRequest
import com.karyo.auth.service.ClientService
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

/**
 * Goods-owner administration.
 *
 * Reads are VIEWER-and-up but tenant-scoped in [ClientService]: an ops principal sees every
 * client, a goods-owner principal sees only itself.
 *
 * Writes (and the consistency report) require `user-admin` — the same composite role UserResource
 * uses and the one the frontend AdminGuard gates the admin routes on. Reads keep the broader list:
 * ordinary users legitimately need to resolve a goods-owner name, and reads are tenant-scoped in
 * the service.
 */
@Path("/api/v1/clients")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ClientResource(
    private val clientService: ClientService,
) {

    @GET
    @RolesAllowed("VIEWER", "OPERATOR", "RECEIVER", "MANAGER", "ADMIN")
    fun list(): List<ClientResponse> = clientService.list()

    // Declared before @Path("/{id}") so "consistency" is not captured as an id.
    @GET
    @Path("/consistency")
    @RolesAllowed("user-admin")
    fun consistency(): ClientConsistencyReport = clientService.consistencyReport()

    @GET
    @Path("/{id}")
    @RolesAllowed("VIEWER", "OPERATOR", "RECEIVER", "MANAGER", "ADMIN")
    fun get(@PathParam("id") id: Long): ClientResponse = clientService.requireById(id)

    @POST
    @RolesAllowed("user-admin")
    fun create(@Valid request: CreateClientRequest): Response =
        Response.status(Response.Status.CREATED).entity(clientService.create(request)).build()

    @PUT
    @Path("/{id}")
    @RolesAllowed("user-admin")
    fun update(@PathParam("id") id: Long, @Valid request: UpdateClientRequest): ClientResponse =
        clientService.update(id, request)

    @POST
    @Path("/{id}/deactivate")
    @RolesAllowed("user-admin")
    fun deactivate(@PathParam("id") id: Long): ClientResponse = clientService.deactivate(id)

    @POST
    @Path("/{id}/reactivate")
    @RolesAllowed("user-admin")
    fun reactivate(@PathParam("id") id: Long): ClientResponse = clientService.reactivate(id)
}

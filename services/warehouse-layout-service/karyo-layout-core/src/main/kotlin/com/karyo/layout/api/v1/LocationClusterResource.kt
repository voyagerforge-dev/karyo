package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.layout.dto.CreateLocationClusterRequest
import com.karyo.layout.dto.LocationClusterResponse
import com.karyo.layout.service.LocationClusterService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/location-clusters")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class LocationClusterResource(
    private val locationClusterService: LocationClusterService,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listClusters(@BeanParam pagination: PaginationParams): PaginatedResponse<LocationClusterResponse> =
        locationClusterService.listAllPaginated(pagination)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getCluster(@PathParam("id") id: Long): LocationClusterResponse =
        locationClusterService.findById(id)

    @POST
    @RolesAllowed("layout-write")
    fun createCluster(@Valid request: CreateLocationClusterRequest): Response {
        val cluster = locationClusterService.create(request)
        return Response.status(Response.Status.CREATED).entity(cluster).build()
    }
}

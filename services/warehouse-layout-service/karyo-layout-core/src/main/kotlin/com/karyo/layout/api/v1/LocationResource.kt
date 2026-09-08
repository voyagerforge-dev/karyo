package com.karyo.layout.api.v1

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.layout.dto.*
import com.karyo.layout.service.LocationDocumentService
import com.karyo.layout.service.LocationService
import com.karyo.security.TenantContext
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/api/v1/locations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class LocationResource(
    private val locationService: LocationService,
    private val locationDocumentService: LocationDocumentService,
    private val tenantContext: TenantContext,
) {

    @GET
    @RolesAllowed("layout-read")
    fun listLocations(
        @QueryParam("areaId") areaId: Long?,
        @QueryParam("zoneId") zoneId: Long?,
        @QueryParam("plcCode") plcCode: String?,
        @BeanParam pagination: PaginationParams,
    ): PaginatedResponse<LocationResponse> =
        locationService.listLocationsPaginated(tenantContext.clientId, areaId, zoneId, plcCode, pagination)

    @GET
    @Path("/{id}")
    @RolesAllowed("layout-read")
    fun getLocation(@PathParam("id") id: Long): LocationResponse =
        locationService.findById(id, tenantContext.clientId)

    @POST
    @RolesAllowed("layout-write")
    fun createLocation(@Valid request: CreateLocationRequest): Response {
        val location = locationService.createLocation(request, tenantContext.clientId)
        return Response.status(Response.Status.CREATED).entity(location).build()
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun updateLocation(@PathParam("id") id: Long, @Valid request: UpdateLocationRequest): LocationResponse =
        locationService.updateLocation(id, request, tenantContext.clientId)

    @POST
    @Path("/{id}/lock")
    @RolesAllowed("layout-write")
    fun lockLocation(@PathParam("id") id: Long, @Valid request: LockLocationRequest): LocationResponse =
        locationService.lockLocation(id, request, tenantContext.clientId)

    @GET
    @Path("/by-scan-code/{code}")
    @RolesAllowed("layout-read")
    fun findByScanCode(@PathParam("code") code: String): LocationResponse =
        locationService.findByScanCode(code, tenantContext.clientId)

    // D11: storage-location ZPL barcode label — Code128 of scanCode ?: name + name + zone/area.
    // `?store=true` opt-in archives via DocumentStore (Task 2, docstore-templates sprint).
    @GET
    @Path("/{id}/label.zpl")
    @Produces("text/plain")
    @RolesAllowed("layout-read")
    fun labelZpl(
        @PathParam("id") id: Long,
        @QueryParam("store") @DefaultValue("false") store: Boolean,
    ): String = locationDocumentService.labelZpl(id, tenantContext.clientId, store)

    @DELETE
    @Path("/{id}")
    @RolesAllowed("layout-write")
    fun deleteLocation(@PathParam("id") id: Long): Response {
        locationService.delete(id, tenantContext.clientId)
        return Response.noContent().build()
    }
}

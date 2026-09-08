package com.karyo.auth.api.v1

import com.karyo.auth.config.SystemPropertyCatalog
import com.karyo.auth.config.SystemPropertyService
import com.karyo.auth.dto.SystemPropertyView
import com.karyo.auth.dto.UpsertSystemPropertyRequest
import com.karyo.common.exception.ProblemDetail
import com.karyo.security.TenantContext
import com.karyo.security.TenantScope
import com.karyo.security.readScope
import com.karyo.security.writeScope
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.validation.Valid
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo

/**
 * SC16 runtime-config administration (`user-admin`, same composite role the frontend
 * AdminGuard gates the admin routes on).
 *
 * Scoping mirrors `JournalResource`/`ClientResource` doctrine: an OWNER principal reads and
 * writes only its own client's rows; an ops (SYS) principal defaults to client 0 (the
 * instance-wide scope) and may target any client via `?clientId=` / body `clientId`. An
 * OWNER naming another client is a bad caller target → 422 (an upsert names no existing
 * row, so there is nothing whose existence a 404 would have to hide).
 */
@Path("/api/v1/system-properties")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SystemPropertyResource(
    private val service: SystemPropertyService,
    private val catalog: SystemPropertyCatalog,
    private val tenantContext: TenantContext,
) {

    @Context
    lateinit var uriInfo: UriInfo

    /** Effective view: every catalog key (metadata + resolved value + source) plus stored extras. */
    @GET
    @RolesAllowed("user-admin")
    fun list(@QueryParam("clientId") clientId: Long?): List<SystemPropertyView> =
        service.effectiveView(targetClient(clientId, tenantContext.readScope()))

    /**
     * Upsert. 422 on a catalog type violation or an out-of-scope target client; 403 when an
     * OWNER principal writes an operator-controlled (`ownerWritable=false`) catalog key.
     */
    @PUT
    @Path("/{key}")
    @RolesAllowed("user-admin")
    fun put(@PathParam("key") key: String, @Valid request: UpsertSystemPropertyRequest): Response {
        requireOwnerWritable(key)
        val clientId = targetClient(request.clientId, tenantContext.writeScope())
        return try {
            val row = service.set(clientId, key, request.context, request.value, request.description)
            Response.ok(service.viewOf(row)).build()
        } catch (e: IllegalArgumentException) {
            problem(UNPROCESSABLE, "system-property-invalid-value", e.message ?: "Invalid value for '$key'")
        }
    }

    /** Removes the stored row so the key reverts to its fallback. 404 if no row is stored. */
    @DELETE
    @Path("/{key}")
    @RolesAllowed("user-admin")
    fun delete(
        @PathParam("key") key: String,
        @QueryParam("context") context: String?,
        @QueryParam("clientId") clientId: Long?,
    ): Response {
        requireOwnerWritable(key)
        val target = targetClient(clientId, tenantContext.writeScope())
        return if (service.delete(target, key, context)) {
            Response.noContent().build()
        } else {
            problem(
                Response.Status.NOT_FOUND.statusCode, "system-property-not-found",
                "No stored value for key '$key' (client $target, context ${context ?: "none"})",
            )
        }
    }

    /**
     * An operator-controlled catalog key (`ownerWritable=false`, e.g. the over-receipt hard
     * stop) may only be written/deleted by an ops principal — otherwise a goods-owner admin
     * could store its own client row above the client-0/env fallback and loosen an ops-set
     * hard stop. 403 (not 404/422): the key's existence is public catalog metadata, the
     * refusal is purely about privilege.
     */
    private fun requireOwnerWritable(key: String) {
        val def = catalog.byKey(key) ?: return
        if (!def.ownerWritable && tenantContext.writeScope() is TenantScope.Owner) {
            throw WebApplicationException(
                problem(
                    Response.Status.FORBIDDEN.statusCode, "system-property-owner-forbidden",
                    "Key '$key' is operator-controlled and cannot be modified by a goods-owner principal",
                ),
            )
        }
    }

    /** SYS targets any client (default 0); an OWNER may only name itself. */
    private fun targetClient(requested: Long?, scope: TenantScope): Long = when (scope) {
        TenantScope.Unscoped -> requested ?: 0L
        is TenantScope.Owner ->
            if (requested == null || requested == scope.clientId) {
                scope.clientId
            } else {
                throw WebApplicationException(
                    problem(
                        UNPROCESSABLE, "system-property-target-forbidden",
                        "A goods-owner principal cannot target client $requested",
                    ),
                )
            }
    }

    private fun problem(status: Int, type: String, detail: String): Response {
        val body = ProblemDetail(
            type = "https://karyo.com/errors/$type",
            title = type.replace("-", " ").replaceFirstChar { it.uppercase() },
            status = status,
            detail = detail,
            instance = uriInfo.requestUri.path,
        )
        return Response.status(status).entity(body).build()
    }

    companion object {
        private const val UNPROCESSABLE = 422
    }
}

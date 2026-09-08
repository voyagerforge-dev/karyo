package com.karyo.demo.api.v1

import com.karyo.demo.config.DemoConfig
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.PreMatching
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider

/**
 * Makes /api/v1/demo routes invisible (404) whenever the demo engine is disabled — runs BEFORE
 * the @RolesAllowed security interceptor (@PreMatching), so even a caller with neither ADMIN nor
 * MANAGER gets 404 (not 403) in real prod. This is the enforcement of the "inert/invisible in
 * prod" invariant; DemoResource keeps @RolesAllowed for the enabled case (ADMIN or MANAGER
 * seed/reset).
 */
@Provider
@PreMatching
class DemoEnabledFilter(private val config: DemoConfig) : ContainerRequestFilter {
    override fun filter(ctx: ContainerRequestContext) {
        val path = ctx.uriInfo.path.trimStart('/')
        if (path.startsWith("api/v1/demo") && !config.enabled) {
            ctx.abortWith(Response.status(Response.Status.NOT_FOUND).build())
        }
    }
}

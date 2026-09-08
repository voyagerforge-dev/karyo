package com.karyo.demo.api.v1

import com.karyo.demo.config.DemoConfig
import com.karyo.demo.dto.DemoSeedSummary
import com.karyo.demo.dto.DemoStatusResponse
import com.karyo.demo.service.DemoDataService
import jakarta.annotation.security.RolesAllowed
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Demo-only data engine. Gated behind KARYO_DEMO=on (config) AND
 * @RolesAllowed("ADMIN", "MANAGER"); returns 404 (not 403) when disabled so the endpoint is
 * invisible/inert in real prod.
 *
 * MANAGER was added in Task 10 (defect-burndown, 2026-07-31): ADMIN acts as SYS (client 0) and
 * can never see the ACME (client 1) data `karyo-demo` seeds (`CLIENT_ID = 1` hardcoded in the
 * generator) -- ADMIN-only seed/reset was a UX contradiction (the only role that can trigger the
 * seed can't view its own result). MANAGER can trigger and view it. Prod risk is unchanged:
 * `DemoEnabledFilter` 404s every route under `/api/v1/demo` pre-security whenever KARYO_DEMO is
 * off, regardless of role.
 */
@Path("/api/v1/demo")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class DemoResource(
    private val config: DemoConfig,
    private val service: DemoDataService,
) {
    private fun requireEnabled() {
        if (!config.enabled) throw NotFoundException()
    }

    /**
     * Gate-discovery probe (LicenseResource precedent): 200 iff KARYO_DEMO is on.
     * VIEWER-reachable (unlike seed/reset) -- the frontend uses this to decide whether to mount
     * SampleDataCard / the palette's demo commands at all, so every authenticated role needs to
     * be able to ask. When disabled the route 404s twice over: `DemoEnabledFilter` pre-matching
     * (before security even runs) AND this `requireEnabled()` belt -- 404-means-off IS the
     * probe contract, there is no distinct "disabled" body.
     */
    @GET @Path("/status") @RolesAllowed("VIEWER")
    fun status(): DemoStatusResponse {
        requireEnabled()
        return DemoStatusResponse(enabled = true)
    }

    @POST @Path("/seed") @RolesAllowed("ADMIN", "MANAGER")
    fun seed(): DemoSeedSummary {
        requireEnabled()
        // Reset FIRST so seeding is idempotent (WORKLIST: re-seed 500'd on unit_loads dup key).
        // Two bean calls from the resource ON PURPOSE: reset()'s 11 @CacheInvalidateAll
        // interceptors only fire across a bean boundary — folding reset into seed() as a
        // self-call would silently skip cache eviction (the 2026-07-21 stale-id bug).
        service.reset()
        return service.seed()
    }

    @POST @Path("/reset") @RolesAllowed("ADMIN", "MANAGER")
    fun reset(): Response {
        requireEnabled()
        service.reset()
        return Response.noContent().build()
    }
}

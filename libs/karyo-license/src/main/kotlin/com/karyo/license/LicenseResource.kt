package com.karyo.license

import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.security.PermitAll
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Anonymous discovery body: the edition of the deployed build and nothing else. */
data class LicenseEditionInfo(val edition: String)

/** Authenticated discovery body: the edition plus the engines this deployment may use. */
data class LicenseInfo(val edition: String, val entitlements: Set<String>)

@Path("/api/v1/license")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
class LicenseResource(
    private val licenseService: LicenseService,
    private val installedModules: Instance<LicensedModuleInstallation>,
    private val identity: SecurityIdentity,
) {
    /**
     * Stays reachable without credentials so the discovery contract never 404s or fails a client,
     * but an unauthenticated caller only learns the edition: no entitlement list, no engine name,
     * no count, and a body that is identical whether or not the deployment is licensed, so no paid
     * inventory can be reconstructed from it. Authenticated callers — the UI gates that need it —
     * read the entitlement detail.
     */
    @GET
    @PermitAll
    fun get(): Response {
        val installedEntitlements = LicenseEdition.installedEntitlements(installedModules)
        val edition = LicenseEdition.of(installedEntitlements)
        if (identity.isAnonymous) {
            return Response.ok(LicenseEditionInfo(edition)).build()
        }
        val availableEntitlements = licenseService.entitlements()
            .filterTo(sortedSetOf()) { it in installedEntitlements }
        return Response.ok(LicenseInfo(edition, availableEntitlements)).build()
    }
}

package com.karyo.security

import jakarta.enterprise.context.RequestScoped

/**
 * Request-scoped security context populated by TenantFilter from JWT claims.
 * Fields per security-architecture.md: clientId, tenantCode, username, roles, permissions, warehouseId, locale.
 */
@RequestScoped
class TenantContext {
    var clientId: Long = 0
    var tenantCode: String = ""
    var username: String = "system"
    var roles: Set<String> = emptySet()
    var permissions: Set<String> = emptySet()
    var warehouseId: String? = null
    var locale: String = "en"

    /**
     * Whether this principal is operating-company staff or a goods owner. Defaults to
     * [PrincipalKind.OWNER] — the restrictive option — so a request that never passed through
     * [TenantFilter] (direct CDI calls, @Scheduled jobs, tests) is owner-scoped, not privileged.
     */
    var principalKind: PrincipalKind = PrincipalKind.OWNER

    companion object {

        /**
         * A synthetic, strictly-scoped [TenantContext] for callers that already carry an explicit
         * `clientId` and must never read ambient state: `@Scheduled` sweeps, and cross-module SPI
         * ports invoked directly rather than through REST. Both run with the request scope active
         * but UNPRIMED (no [TenantFilter] ran), so the injected bean's [clientId] is still the
         * default 0 -- reading it there is a silent wrong-tenant read, not an error.
         *
         * [principalKind] is [PrincipalKind.OWNER] deliberately: [writeScope]/[readScope] on OWNER
         * are strict `clientId` equality, never the OPS-style unscoped view, which is exactly the
         * guarantee an explicit-`clientId` overload exists to provide. Never derive one of these
         * from an ambient context -- build it from the explicit `clientId` alone.
         *
         * [actor] is ATTRIBUTION ONLY -- it names who the audit rows written under this context
         * belong to, and never widens what the context may read or write (that is [clientId] plus
         * [PrincipalKind.OWNER], both fixed above). Not every explicit-`clientId` caller is a
         * scheduler: several are plain REST paths that hold an explicit `clientId` because the
         * entity, not the request, owns the goods, and a real human IS on those requests. Such a
         * caller passes the ambient principal's username through, so the journal keeps naming the
         * operator. Callers with no human on the request (`@Scheduled` sweeps, port-to-port calls
         * on an unprimed request scope) leave it at the default `"system"` -- which is also what
         * an unprimed [TenantContext.username] already reads, so passing it through is safe there
         * too.
         */
        fun ownerScoped(clientId: Long, actor: String = "system"): TenantContext = TenantContext().apply {
            this.clientId = clientId
            this.principalKind = PrincipalKind.OWNER
            this.username = actor
        }
    }
}

package com.karyo.security

import io.quarkus.security.identity.SecurityIdentity
import jakarta.inject.Inject
import jakarta.json.JsonNumber
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.jwt.JsonWebToken
import org.slf4j.MDC

/**
 * JAX-RS filter that extracts JWT claims into TenantContext and populates MDC
 * for tenant-scoped logging (per observability-architecture.md).
 */
@Provider
class TenantFilter : ContainerRequestFilter {
    @Inject
    lateinit var tenantContext: TenantContext

    @Inject
    lateinit var securityIdentity: SecurityIdentity

    override fun filter(requestContext: ContainerRequestContext) {
        if (securityIdentity.isAnonymous) return
        val jwt = securityIdentity.principal as? JsonWebToken ?: return

        // Populate TenantContext from JWT claims.
        // SmallRye JWT / Quarkus OIDC returns custom numeric claims as jakarta.json.JsonNumber
        // (a JSON-P type), which is NOT a java.lang.Number — handle it explicitly before
        // the generic Number branch so non-zero client_id values (e.g. manager=1) propagate.
        tenantContext.clientId = when (val raw = jwt.getClaim<Any>("client_id")) {
            is JsonNumber -> raw.longValue()
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0
            else -> 0
        }
        tenantContext.tenantCode = jwt.getClaim<String>("tenant_code") ?: ""
        tenantContext.username = jwt.name ?: "unknown"
        tenantContext.roles = securityIdentity.roles ?: emptySet()
        tenantContext.permissions = jwt.getClaim<Set<String>>("permissions") ?: emptySet()
        tenantContext.warehouseId = jwt.getClaim<String>("warehouse_id")
        tenantContext.locale = jwt.getClaim<String>("locale") ?: "en"
        // Fail-closed: absent/unrecognized -> OWNER. Tokens minted before this claim existed
        // therefore keep the strict pre-change read behavior.
        tenantContext.principalKind = PrincipalKind.fromClaim(jwt.getClaim<String>(PrincipalKind.CLAIM))

        // Populate MDC for structured logging (per observability-architecture.md)
        MDC.put("tenantId", tenantContext.clientId.toString())
        MDC.put("userId", tenantContext.username)
        MDC.put("correlationId", requestContext.getHeaderString("X-Correlation-Id") ?: "")
    }
}

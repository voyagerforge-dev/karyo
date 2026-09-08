package com.karyo.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for the principal-kind / read-scope model. No Quarkus context needed —
 * TenantContext is a plain bean here, constructed directly.
 *
 * The fail-closed default is the safety property that makes this change shippable ahead of
 * the Keycloak realm update: every token in existence lacks the claim, so every principal
 * resolves OWNER and keeps today's strict clientId behavior.
 */
class TenantScopeTest {

    @Test
    fun `fromClaim maps ops and owner`() {
        assertThat(PrincipalKind.fromClaim("ops")).isEqualTo(PrincipalKind.OPS)
        assertThat(PrincipalKind.fromClaim("owner")).isEqualTo(PrincipalKind.OWNER)
    }

    @Test
    fun `fromClaim is case-insensitive`() {
        assertThat(PrincipalKind.fromClaim("OPS")).isEqualTo(PrincipalKind.OPS)
        assertThat(PrincipalKind.fromClaim("Owner")).isEqualTo(PrincipalKind.OWNER)
    }

    @Test
    fun `fromClaim fails closed to OWNER for absent, blank or unrecognized values`() {
        assertThat(PrincipalKind.fromClaim(null)).isEqualTo(PrincipalKind.OWNER)
        assertThat(PrincipalKind.fromClaim("")).isEqualTo(PrincipalKind.OWNER)
        assertThat(PrincipalKind.fromClaim("   ")).isEqualTo(PrincipalKind.OWNER)
        assertThat(PrincipalKind.fromClaim("garbage")).isEqualTo(PrincipalKind.OWNER)
        assertThat(PrincipalKind.fromClaim("admin")).isEqualTo(PrincipalKind.OWNER)
    }

    @Test
    fun `TenantContext defaults to OWNER`() {
        assertThat(TenantContext().principalKind).isEqualTo(PrincipalKind.OWNER)
    }

    @Test
    fun `owner scope permits only its own clientId`() {
        val scope = TenantScope.Owner(1L)
        assertThat(scope.permits(1L)).isTrue()
        assertThat(scope.permits(2L)).isFalse()
        assertThat(scope.permits(0L)).isFalse()
    }

    @Test
    fun `unscoped permits every clientId`() {
        assertThat(TenantScope.Unscoped.permits(0L)).isTrue()
        assertThat(TenantScope.Unscoped.permits(1L)).isTrue()
        assertThat(TenantScope.Unscoped.permits(999L)).isTrue()
    }

    @Test
    fun `readScope is unscoped for OPS and owner-scoped for OWNER`() {
        val ops = TenantContext().apply { clientId = 7L; principalKind = PrincipalKind.OPS }
        assertThat(ops.readScope()).isEqualTo(TenantScope.Unscoped)

        val owner = TenantContext().apply { clientId = 7L; principalKind = PrincipalKind.OWNER }
        assertThat(owner.readScope()).isEqualTo(TenantScope.Owner(7L))
    }
}

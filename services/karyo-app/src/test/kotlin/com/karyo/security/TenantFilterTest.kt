package com.karyo.security

import io.mockk.every
import io.mockk.mockk
import io.quarkus.security.identity.SecurityIdentity
import jakarta.json.Json
import jakarta.ws.rs.container.ContainerRequestContext
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plain unit test (no @QuarkusTest / no container) for TenantFilter's client_id claim handling.
 *
 * Regression for: SmallRye JWT / Quarkus OIDC returns numeric claims as jakarta.json.JsonNumber
 * (a JSON-P type, NOT java.lang.Number). The old `when` fell through to `else -> 0`, so any
 * real Keycloak token with a numeric client_id was silently treated as 0 (unauthenticated tenant).
 * The fix adds `is JsonNumber -> raw.longValue()` before the generic Number branch.
 *
 * Coverage: all three branches of the `when` block in TenantFilter.filter().
 *
 * Mocking note: JsonWebToken.getClaim is a Java generic `<T> T getClaim(String)`. MockK's relaxed
 * mode returns a raw Object instance (not null) for any unstubbed call, which ClassCastExceptions
 * when Kotlin coerces it to String/Set/etc. Fix: catch-all `any()` stub returns null so the
 * filter's ?: fallbacks apply; individual tests then override "client_id" specifically.
 */
class TenantFilterTest {

    private lateinit var filter: TenantFilter
    private lateinit var tenantContext: TenantContext
    private lateinit var securityIdentity: SecurityIdentity
    private lateinit var jwt: JsonWebToken
    private lateinit var ctx: ContainerRequestContext

    @BeforeEach
    fun setUp() {
        filter = TenantFilter()
        tenantContext = TenantContext()
        securityIdentity = mockk()
        jwt = mockk()
        ctx = mockk(relaxed = true) // only used for getHeaderString("X-Correlation-Id")

        // Wire the lateinit fields directly — no CDI needed
        filter.tenantContext = tenantContext
        filter.securityIdentity = securityIdentity

        every { securityIdentity.isAnonymous } returns false
        every { securityIdentity.roles } returns emptySet()
        every { securityIdentity.principal } returns jwt

        // Catch-all: any unstubbed getClaim call returns null so the filter's ?: fallbacks apply.
        // MockK's relaxed mode would return a raw Object for Java-generic `<T> T getClaim(String)`,
        // causing ClassCastException when the filter coerces it to String/Set/etc.
        every { jwt.getClaim<Any>(any<String>()) } returns null

        // jwt.name (from Principal.getName()) — null triggers "unknown" fallback in filter
        every { jwt.name } returns null
    }

    /**
     * REGRESSION: Json.createValue(1L) produces a jakarta.json.JsonNumber.
     * Pre-fix code hit `else -> 0`; post-fix code hits `is JsonNumber -> raw.longValue()`.
     * This test FAILS on the un-patched TenantFilter (clientId would be 0).
     */
    @Test
    fun `JsonNumber client_id claim is parsed correctly - regression for JsonNumber silent zero`() {
        every { jwt.getClaim<Any>("client_id") } returns Json.createValue(1L)

        filter.filter(ctx)

        assertThat(tenantContext.clientId)
            .describedAs("JsonNumber claim must not fall through to else→0")
            .isEqualTo(1L)
    }

    /** String claim — the path exercised by existing @OidcSecurity tests; must stay green. */
    @Test
    fun `String client_id claim is parsed as long`() {
        every { jwt.getClaim<Any>("client_id") } returns "2"

        filter.filter(ctx)

        assertThat(tenantContext.clientId).isEqualTo(2L)
    }

    /** Null / missing claim — fallback to 0 (anonymous-equivalent clientId). */
    @Test
    fun `null client_id claim defaults to zero`() {
        // catch-all already returns null for "client_id"; no additional stub needed
        filter.filter(ctx)

        assertThat(tenantContext.clientId).isEqualTo(0L)
    }
}

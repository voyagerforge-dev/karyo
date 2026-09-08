package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class LicenseMintTest {
    @Test
    fun `minted claims preserve controller-selected values`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val issuedAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plus(30, ChronoUnit.DAYS)
        val entitlements = setOf("forecasting", "monitors")

        val token = TestLicenseMinter.mint(
            privateKeyBase64 = privateKey,
            licenseId = "deterministic-mint",
            customer = "Karyo Test",
            entitlements = entitlements,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )

        val claims = LicenseVerifier().verify(token, publicKey)
        assertNotNull(claims)
        assertEquals("deterministic-mint", claims!!.licenseId)
        assertEquals("Karyo Test", claims.customer)
        assertEquals(entitlements, claims.entitlements)
        assertEquals(expiresAt, claims.expiresAt)
    }
}

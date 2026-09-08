package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Optional

/**
 * [LicenseService] is constructed directly (no Quarkus/CDI container) since its verifier is a
 * pure class and its 3 config props are plain constructor args — this test never touches the
 * real vendor key, only test-generated keypairs.
 */
class LicenseServiceTest {

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun publicKeyBase64(kp: KeyPair) = Base64.getEncoder().encodeToString(kp.public.encoded)
    private fun privateKeyBase64(kp: KeyPair) = Base64.getEncoder().encodeToString(kp.private.encoded)

    @Test
    fun `valid signed license entitles exactly its payload keys`() {
        val kp = generateKeyPair()
        val token = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64(kp),
            entitlements = setOf("monitors", "slotting"),
        )

        val service = LicenseService(
            licenseKey = token,
            publicKeyOverride = publicKeyBase64(kp),
            legacyRaw = Optional.of("none"),
        )

        assertTrue(service.isEntitled("monitors"))
        assertTrue(service.isEntitled("slotting"))
        assertFalse(service.isEntitled("forecasting"))
        assertEquals(setOf("monitors", "slotting"), service.entitlements())
    }

    @Test
    fun `expired signed license locks everything and ignores the legacy fallback`() {
        val kp = generateKeyPair()
        val expiredToken = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64(kp),
            entitlements = setOf("monitors"),
            expiresAt = Instant.now().minus(1, ChronoUnit.DAYS),
        )

        val service = LicenseService(
            licenseKey = expiredToken,
            publicKeyOverride = publicKeyBase64(kp),
            // A present-but-invalid license must NOT fall back to the legacy env — it locks.
            legacyRaw = Optional.of("monitors,forecasting"),
        )

        assertFalse(service.isEntitled("monitors"))
        assertEquals(emptySet<String>(), service.entitlements())
    }

    @Test
    fun `signed license from a foreign key locks everything and ignores the legacy fallback`() {
        val realKey = generateKeyPair()
        val foreignKey = generateKeyPair()
        val forgedToken = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64(foreignKey),
            entitlements = setOf("monitors", "forecasting", "slotting", "simulation"),
        )

        val service = LicenseService(
            licenseKey = forgedToken,
            publicKeyOverride = publicKeyBase64(realKey),
            legacyRaw = Optional.of("monitors"),
        )

        assertFalse(service.isEntitled("monitors"))
        assertTrue(service.entitlements().isEmpty())
    }

    @Test
    fun `no signed license falls back to the legacy env entitlements`() {
        val kp = generateKeyPair()

        val service = LicenseService(
            licenseKey = "none",
            publicKeyOverride = publicKeyBase64(kp),
            legacyRaw = Optional.of("monitors,forecasting"),
        )

        assertTrue(service.isEntitled("monitors"))
        assertTrue(service.isEntitled("forecasting"))
        assertFalse(service.isEntitled("slotting"))
    }

    @Test
    fun `no signed license and no legacy env leaves everything locked`() {
        val kp = generateKeyPair()

        val service = LicenseService(
            licenseKey = "none",
            publicKeyOverride = publicKeyBase64(kp),
            legacyRaw = Optional.empty(),
        )

        assertTrue(service.entitlements().isEmpty())
        assertFalse(service.isEntitled("monitors"))
    }
}

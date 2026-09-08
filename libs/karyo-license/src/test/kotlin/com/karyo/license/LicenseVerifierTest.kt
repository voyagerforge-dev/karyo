package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class LicenseVerifierTest {

    private val verifier = LicenseVerifier()

    private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun publicKeyBase64(kp: KeyPair) = Base64.getEncoder().encodeToString(kp.public.encoded)
    private fun privateKeyBase64(kp: KeyPair) = Base64.getEncoder().encodeToString(kp.private.encoded)

    @Test
    fun `valid token verifies and returns the payload claims`() {
        val kp = generateKeyPair()
        val token = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64(kp),
            licenseId = "lic-1",
            customer = "Acme WMS",
            entitlements = setOf("monitors", "forecasting"),
        )

        val claims = verifier.verify(token, publicKeyBase64(kp))

        assertNotNull(claims)
        assertEquals("lic-1", claims!!.licenseId)
        assertEquals("Acme WMS", claims.customer)
        assertEquals(setOf("monitors", "forecasting"), claims.entitlements)
        assertNull(claims.expiresAt)
    }

    @Test
    fun `tampered payload fails the signature check`() {
        val kp = generateKeyPair()
        val token = TestLicenseMinter.mint(privateKeyBase64 = privateKeyBase64(kp))
        val (payload, signature) = token.split(".", limit = 2)
        val flippedLastChar = if (payload.last() == 'A') 'B' else 'A'
        val tampered = "${payload.dropLast(1)}$flippedLastChar.$signature"

        assertNull(verifier.verify(tampered, publicKeyBase64(kp)))
    }

    @Test
    fun `expired token fails`() {
        val kp = generateKeyPair()
        val token = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64(kp),
            issuedAt = Instant.now().minus(400, ChronoUnit.DAYS),
            expiresAt = Instant.now().minus(1, ChronoUnit.DAYS),
        )

        assertNull(verifier.verify(token, publicKeyBase64(kp)))
    }

    @Test
    fun `token signed by a different key fails`() {
        val signer = generateKeyPair()
        val otherKeyHolder = generateKeyPair()
        val token = TestLicenseMinter.mint(privateKeyBase64 = privateKeyBase64(signer))

        assertNull(verifier.verify(token, publicKeyBase64(otherKeyHolder)))
    }

    @Test
    fun `malformed token without a dot fails`() {
        val kp = generateKeyPair()

        assertNull(verifier.verify("not-a-valid-token", publicKeyBase64(kp)))
    }

    @Test
    fun `malformed base64 fails`() {
        val kp = generateKeyPair()

        assertNull(verifier.verify("!!!not-base64!!!.also-not-base64!!!", publicKeyBase64(kp)))
    }

    @Test
    fun `empty or missing token fails`() {
        val kp = generateKeyPair()

        assertNull(verifier.verify("", publicKeyBase64(kp)))
        assertNull(verifier.verify(".", publicKeyBase64(kp)))
    }
}

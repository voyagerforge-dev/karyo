package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Controller utility, NOT a production code path: generates a fresh Ed25519 keypair and mints a
 * demo license token with it, printing all three values to stdout.
 *
 * Run it once via:
 * `./gradlew :libs:karyo-license:test --tests "com.karyo.license.LicenseKeygenTest"`
 *
 * and capture the printed output:
 *  - `PUBLIC_KEY_BASE64`  -> replace the single line in
 *    `src/main/resources/com/karyo/license/vendor-public-key.txt`, the key's one declared home.
 *    [VendorKey] reads it from there, so that one line is the whole code change; do not paste
 *    it into any source file. Re-mint every live licence under the new key BEFORE deploying it:
 *    a licence signed by the old key is indistinguishable from a forgery once the key moves.
 *  - `PRIVATE_KEY_BASE64` -> hand to the deploy controller out-of-band; NEVER commit to git.
 *  - `DEMO_LICENSE_TOKEN` -> wire as `KARYO_LICENSE_KEY` in `scripts/.env.prod` (gitignored).
 *
 * Also doubles as a real regression test: every run mints a fresh keypair + token and asserts
 * the round trip (mint -> verify -> claims match) actually works.
 *
 * This is the key ROTATION tool only. To re-mint a token under the key that is already bundled
 * (new entitlements, new expiry, new customer), use `LicenseMintOperationalTest` via
 * `scripts/mint-license.sh`; it signs with the existing private key from
 * `scripts/.license-signing-key` and verifies against [VendorKey.PUBLIC_KEY_BASE64].
 */
class LicenseKeygenTest {

    @Test
    fun `generate a vendor keypair and mint a demo license token`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)

        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(365, ChronoUnit.DAYS)
        val entitlements = setOf("monitors", "forecasting", "slotting", "simulation")
        val token = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64,
            licenseId = "demo-${issuedAt.epochSecond}",
            customer = "Karyo Demo",
            entitlements = entitlements,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
        )

        println(
            """
            === LicenseKeygen output — capture and bundle the public key; NEVER commit the private key ===
            PUBLIC_KEY_BASE64=$publicKeyBase64
            PRIVATE_KEY_BASE64=$privateKeyBase64
            DEMO_LICENSE_TOKEN=$token
            """.trimIndent(),
        )

        val claims = LicenseVerifier().verify(token, publicKeyBase64)
        assertNotNull(claims, "freshly minted token must verify against its own public key")
        assertEquals(entitlements, claims!!.entitlements)
        assertEquals("Karyo Demo", claims.customer)
    }
}

package com.karyo.license

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class LicenseMintOperationalTest {
    @Test
    fun `re-mint a license token under the existing vendor key`() {
        val keyFile = requireNotNull(System.getProperty("karyo.mint.keyFile")) {
            "karyo.mint.keyFile is required"
        }
        val privateKeyBase64 = readPrivateKey(File(keyFile))
        val entitlements = requireNotNull(System.getProperty("karyo.mint.entitlements")) {
            "karyo.mint.entitlements is required"
        }.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        require(entitlements.isNotEmpty()) { "karyo.mint.entitlements must name at least one key" }
        val customer = System.getProperty("karyo.mint.customer") ?: "Karyo Demo"
        // Optional: the stable goods-owner identifier the commercial distribution path scopes a
        // download to. Omitted unless asked for, so an ordinary runtime licence is unchanged.
        val customerId = System.getProperty("karyo.mint.customerId")?.takeIf { it.isNotBlank() }
        val days = (System.getProperty("karyo.mint.days") ?: "365").toLong()
        require(days > 0) { "karyo.mint.days must be positive" }
        val issuedAt = Instant.now()
        val licenseId = System.getProperty("karyo.mint.licenseId") ?: "demo-${issuedAt.epochSecond}"
        val expiresAt = issuedAt.plus(days, ChronoUnit.DAYS)

        val token = TestLicenseMinter.mint(
            privateKeyBase64 = privateKeyBase64,
            licenseId = licenseId,
            customer = customer,
            entitlements = entitlements,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            customerId = customerId,
        )

        val claims = LicenseVerifier().verify(token, VendorKey.PUBLIC_KEY_BASE64)
        assertNotNull(claims, "token must verify against the bundled vendor key")
        assertEquals(entitlements, claims!!.entitlements)
        assertEquals(customer, claims.customer)
        assertEquals(licenseId, claims.licenseId)
        assertEquals(expiresAt.epochSecond, claims.expiresAt?.epochSecond)
        // LicenseClaims deliberately does not carry customerId - the runtime gate has no use for
        // it - so assert it landed in the signed payload itself, which is what the distribution
        // service reads. Without this, --customer-id could silently do nothing.
        if (customerId != null) {
            val payloadJson = String(
                Base64.getUrlDecoder().decode(token.substringBefore('.')),
                Charsets.UTF_8,
            )
            assertTrue(
                payloadJson.contains("\"customerId\":\"$customerId\""),
                "minted payload must carry the requested customerId",
            )
        }
        println("LICENSE_TOKEN=$token")
    }

    private fun readPrivateKey(file: File): String {
        require(file.isFile) { "key file not found: ${file.path}" }
        val line = file.readLines().firstOrNull { it.startsWith("PRIVATE_KEY_BASE64=") }
        return requireNotNull(line) { "no PRIVATE_KEY_BASE64= line in ${file.path}" }
            .substringAfter("=").trim()
    }
}

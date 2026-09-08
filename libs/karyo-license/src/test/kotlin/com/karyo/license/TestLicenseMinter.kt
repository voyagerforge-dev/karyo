package com.karyo.license

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Test-only token signer — the inverse of [LicenseVerifier], used by [LicenseVerifierTest],
 * [LicenseServiceTest] and [LicenseKeygenTest] to mint tokens against a test/generated keypair.
 * Never used from production code (real license tokens are signed by the vendor out-of-band,
 * with the private key never checked into this repo).
 */
object TestLicenseMinter {
    private val mapper = ObjectMapper()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun mint(
        privateKeyBase64: String,
        licenseId: String = "test-license",
        customer: String = "Test Customer",
        entitlements: Set<String> = setOf("monitors"),
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant? = null,
        customerId: String? = null,
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "licenseId" to licenseId,
        )
        // Optional, and omitted entirely when absent so a token minted without it is byte-for-byte
        // what this minter produced before the claim existed. [LicenseVerifier] ignores unknown
        // fields, so adding it breaks no deployed licence and needs no runtime change: `customerId`
        // identifies a goods-owner to the COMMERCIAL DISTRIBUTION path, which decides which built
        // image a licence may fetch. The runtime entitlement gate has no use for it and does not
        // read it. A distribution licence without this claim is refused outright rather than
        // treated as an unscoped grant.
        if (customerId != null) payload["customerId"] = customerId
        payload.putAll(
            linkedMapOf(
                "customer" to customer,
                "entitlements" to entitlements.toList(),
                "issuedAt" to issuedAt.epochSecond,
                "expiresAt" to expiresAt?.epochSecond,
            ),
        )
        val payloadB64 = urlEncoder.encodeToString(mapper.writeValueAsBytes(payload))

        val privateKey: PrivateKey = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)))
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(payloadB64.toByteArray(Charsets.UTF_8))
        }.sign()

        return "$payloadB64.${urlEncoder.encodeToString(signature)}"
    }
}

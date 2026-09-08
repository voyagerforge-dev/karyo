package com.karyo.license

import com.fasterxml.jackson.databind.ObjectMapper
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Pure Ed25519 license-token verifier. No CDI, no config injection — a plain class so it is
 * trivially unit-testable and so [LicenseService] can compute its entitlements once at
 * construction without touching a container.
 *
 * **Token format:** `base64url(payloadJson) + "." + base64url(signature)`. The signature is
 * computed over the UTF-8 bytes of the `base64url(payloadJson)` string — the exact substring
 * before the dot, not the decoded JSON.
 *
 * **Payload JSON:** `{"licenseId": String, "customer": String, "entitlements": [String],
 * "issuedAt": epochSeconds, "expiresAt": epochSeconds|null}`.
 *
 * **Unknown payload fields are ignored, deliberately.** The vendor may mint additional claims
 * that other tooling reads - an optional `customerId` naming the goods owner is one - and this
 * gate has no use for them, which is why [LicenseClaims] does not carry them. Do not tighten
 * parsing to reject fields it does not know: that breaks those consumers while changing no
 * entitlement decision made here.
 *
 * **Public key:** base64-encoded X.509/SPKI DER, verified via JDK 21's native `"Ed25519"`
 * `KeyFactory`/`Signature` providers (no extra crypto dependency).
 */
class LicenseVerifier {
    private val mapper = ObjectMapper()

    /**
     * Verifies [token] against [publicKeyBase64] and returns the decoded [LicenseClaims].
     * Returns null for ANY malformed / bad-signature / expired case — never throws.
     */
    fun verify(token: String, publicKeyBase64: String): LicenseClaims? {
        val dotIndex = token.indexOf('.')
        if (dotIndex <= 0 || dotIndex == token.length - 1) return null
        val payloadB64 = token.substring(0, dotIndex)
        val signatureB64 = token.substring(dotIndex + 1)

        val publicKey = decodePublicKey(publicKeyBase64) ?: return null
        val signatureBytes = decodeUrlBase64(signatureB64) ?: return null

        val signatureValid = runCatching {
            Signature.getInstance("Ed25519").apply {
                initVerify(publicKey)
                update(payloadB64.toByteArray(Charsets.UTF_8))
            }.verify(signatureBytes)
        }.getOrDefault(false)
        if (!signatureValid) return null

        val payloadBytes = decodeUrlBase64(payloadB64) ?: return null
        val claims = parseClaims(payloadBytes) ?: return null

        if (claims.expiresAt != null && claims.expiresAt.isBefore(Instant.now())) return null
        return claims
    }

    private fun parseClaims(payloadBytes: ByteArray): LicenseClaims? {
        val node = runCatching { mapper.readTree(payloadBytes) }.getOrNull() ?: return null

        val licenseId = node.get("licenseId")?.takeIf { it.isTextual }?.asText() ?: return null
        val customer = node.get("customer")?.takeIf { it.isTextual }?.asText() ?: return null

        val entitlementsNode = node.get("entitlements")?.takeIf { it.isArray } ?: return null
        val entitlements = entitlementsNode
            .map { it.asText() }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        val issuedAtNode = node.get("issuedAt")?.takeIf { it.isNumber } ?: return null
        val issuedAt = Instant.ofEpochSecond(issuedAtNode.asLong())

        val expiresAtNode = node.get("expiresAt")
        val expiresAt = when {
            expiresAtNode == null || expiresAtNode.isNull -> null
            expiresAtNode.isNumber -> Instant.ofEpochSecond(expiresAtNode.asLong())
            else -> return null
        }

        return LicenseClaims(licenseId, customer, entitlements, issuedAt, expiresAt)
    }

    private fun decodePublicKey(publicKeyBase64: String): PublicKey? = runCatching {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(keyBytes))
    }.getOrNull()

    private fun decodeUrlBase64(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrNull()
}

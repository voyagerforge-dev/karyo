package com.karyo.license

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.Optional

/**
 * Open-core entitlement gate. Entitlements resolve from a **signed Ed25519 license token**
 * ([LicenseVerifier]) verified against a bundled vendor public key, with the legacy plain-env
 * flag (`karyo.license.entitlements` / `KARYO_LICENSE`) kept only as a dev fallback when no
 * signed license is configured. Resolution order (computed once at construction):
 *
 *  1. `karyo.license.key` set and it verifies (valid signature, not expired) -> entitlements =
 *     the token's payload entitlements.
 *  2. `karyo.license.key` set but does NOT verify (bad signature / expired / malformed) ->
 *     entitlements = empty (locked) — a present-but-invalid license is never silently
 *     downgraded to the legacy fallback.
 *  3. No signed key -> entitlements = the legacy `karyo.license.entitlements` env set.
 */
@ApplicationScoped
class LicenseService(
    // "none" is a non-empty placeholder default: smallrye-config's built-in String converter
    // treats an empty resolved value as "missing" (SRCFG00040), which fails app boot once any
    // bean actually injects LicenseService. Filtered back out below so the default stays empty.
    @ConfigProperty(name = "karyo.license.key", defaultValue = "none")
    private val licenseKey: String,
    // The bundled key cannot be the annotation default: it is read from the classpath at runtime
    // (see [VendorKey]) and `defaultValue` takes a compile-time constant. The sentinel names "no
    // override", resolved to the bundled key below, so the single declared home of the key stays
    // the one file and this class still restates nothing.
    @ConfigProperty(name = "karyo.license.public-key", defaultValue = VendorKey.NOT_OVERRIDDEN)
    private val publicKeyOverride: String,
    // The supported env template sets KARYO_LICENSE= explicitly. SmallRye converts that
    // empty value to absence, so an Optional must accept it without preventing free app boot.
    @ConfigProperty(name = "karyo.license.entitlements", defaultValue = "none")
    private val legacyRaw: Optional<String>,
) {
    private val verifier = LicenseVerifier()

    private val publicKeyBase64: String = VendorKey.resolve(publicKeyOverride)

    private val keys: Set<String> = resolveEntitlements()

    private fun resolveEntitlements(): Set<String> {
        if (licenseKey != "none") {
            val claims = verifier.verify(licenseKey, publicKeyBase64)
            if (claims != null) {
                LOG.info(
                    "Licensed to ${claims.customer} — entitlements ${claims.entitlements}, " +
                        "expires ${claims.expiresAt?.toString() ?: "never"}",
                )
                return claims.entitlements
            }
            LOG.warn("karyo.license.key is set but invalid/expired — paid modules locked")
            return emptySet()
        }

        val raw = legacyRaw.orElse("none")
        val legacy = raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "none" }
            .toSet()
        if (legacy.isNotEmpty()) {
            LOG.info("dev entitlements from KARYO_LICENSE=$raw (no signed license configured)")
        }
        return legacy
    }

    fun entitlements(): Set<String> = keys

    fun isEntitled(moduleKey: String): Boolean = keys.contains(moduleKey.trim().lowercase())

    /** Throws [LicenseRequiredException] when [moduleKey] is not entitled; else returns. */
    fun require(moduleKey: String) {
        if (!isEntitled(moduleKey)) throw LicenseRequiredException(moduleKey)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(LicenseService::class.java)
    }
}

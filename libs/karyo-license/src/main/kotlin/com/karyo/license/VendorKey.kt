package com.karyo.license

/**
 * The bundled vendor Ed25519 public key (base64 X.509/SPKI DER) - verifies signed license
 * tokens minted with the vendor's private key, which never ships in this repo.
 *
 * The value is **not declared here**. It is read from `vendor-public-key.txt`, the file beside
 * this class on the classpath, which is the key's one declared home in the repository and holds
 * the key alone. Do not paste the key back into this source.
 *
 * Generated once via [LicenseKeygenTest] (`libs/karyo-license/src/test`). The matching private
 * key and any license token are provisioned out-of-band and never committed to this repository.
 */
internal object VendorKey {
    /** Resource name, relative to this class's package. */
    const val KEY_RESOURCE = "vendor-public-key.txt"

    /**
     * Sentinel default for `karyo.license.public-key`.
     *
     * [PUBLIC_KEY_BASE64] cannot be an annotation default because it is read at runtime and a
     * `defaultValue` argument must be a compile-time constant. This names "no override supplied"
     * instead, exactly as `karyo.license.key` uses `"none"`, and for the same reason: an empty
     * default would fail app boot with SmallRye `SRCFG00040` once any bean injects the service.
     */
    const val NOT_OVERRIDDEN = "bundled"

    /**
     * The key, resolved once at class initialisation.
     *
     * A missing, unreadable or empty resource throws here, which surfaces as a boot failure
     * naming the file rather than as every licensed module silently locking.
     */
    val PUBLIC_KEY_BASE64: String = readBundledKey()

    /**
     * The key to verify with, given whatever `karyo.license.public-key` resolved to.
     *
     * Lives here rather than in [LicenseService] so the sentinel and the value it stands for
     * stay in one place, and so the substitution is directly testable.
     */
    fun resolve(configured: String): String =
        if (configured == NOT_OVERRIDDEN) PUBLIC_KEY_BASE64 else configured

    /**
     * The whole file format: the key alone, so reading it is a strip.
     *
     * Deliberately without a comment or blank-line rule. A second reader in another language
     * loads the same file, and every rule here would be a rule the two must agree on forever -
     * the drift this single declared home exists to remove. A rotation is therefore a one-line
     * replacement in that file and nothing else in code.
     */
    internal fun parse(text: String): String =
        text.trim().ifEmpty {
            error("$KEY_RESOURCE is empty; it must hold the vendor public key and nothing else")
        }

    private fun readBundledKey(): String {
        val text = VendorKey::class.java.getResourceAsStream(KEY_RESOURCE)?.use {
            it.readBytes().decodeToString()
        } ?: error("$KEY_RESOURCE is missing from the classpath beside ${VendorKey::class.java.name}")

        return parse(text)
    }
}

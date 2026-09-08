package com.karyo.license

import java.time.Instant

/**
 * Verified payload of a signed license token (see [LicenseVerifier]). Only ever produced by a
 * successful [LicenseVerifier.verify] call — a malformed/unsigned/expired token never reaches
 * this type.
 */
data class LicenseClaims(
    val licenseId: String,
    val customer: String,
    val entitlements: Set<String>,
    val issuedAt: Instant,
    val expiresAt: Instant?,
)

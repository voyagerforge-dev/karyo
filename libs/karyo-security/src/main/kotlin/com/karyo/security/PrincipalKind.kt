package com.karyo.security

/**
 * What kind of principal is holding the terminal.
 *
 * - [OPS]   — operating-company staff. In a single-warehouse 3PL they physically handle every
 *             goods owner's stock, so their reads span all owners and their writes are
 *             owner-blind (see [TenantScope]).
 * - [OWNER] — a goods owner (3PL customer). Reads are restricted to their own `client_id`;
 *             this is what makes the paid client read-only portals safe.
 *
 * Resolution is deliberately fail-closed: anything unrecognized is [OWNER], the restrictive
 * option. Every token issued before the `principal_kind` claim existed therefore keeps the
 * pre-existing strict behavior with no read regression.
 */
enum class PrincipalKind {
    OPS,
    OWNER,
    ;

    companion object {
        /** JWT claim name carrying this value. */
        const val CLAIM = "principal_kind"

        fun fromClaim(raw: String?): PrincipalKind =
            when (raw?.trim()?.lowercase()) {
                "ops" -> OPS
                else -> OWNER
            }
    }
}

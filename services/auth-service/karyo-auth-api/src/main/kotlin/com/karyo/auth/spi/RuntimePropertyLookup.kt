package com.karyo.auth.spi

/**
 * In-process read-only lookup onto the SC16 runtime-editable config store, consumed by other
 * modules instead of a cross-module repository dependency — mirror of the [ClientLookup]
 * pattern (contract in auth-api so foreign cores never depend on auth-core).
 *
 * Resolution ladder per call: stored row for [key]/`clientId` → stored client-0 row →
 * MicroProfile config value for the same key → the caller-supplied default (typically the
 * module's injected `@ConfigProperty` value, which thereby becomes the fallback, not the
 * authority). Deliberately unscoped, like [ClientLookup.exists]: callers pass the domain
 * row's own `client_id`, and production call sites are already tenant-guarded.
 *
 * This is a cross-module read contract, not a strategy seam: there is no plausible
 * second implementation, and none should be added.
 */
interface RuntimePropertyLookup {

    fun getString(key: String, clientId: Long, default: String?): String?

    /** Parses "true"/"false" case-insensitively; any other stored/configured value → [default]. */
    fun getBoolean(key: String, clientId: Long, default: Boolean): Boolean

    /** Parses a base-10 integer; any non-numeric stored/configured value → [default]. */
    fun getInt(key: String, clientId: Long, default: Int): Int
}

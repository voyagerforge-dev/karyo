package com.karyo.security

/**
 * The single place tenant-scoping decisions are made.
 *
 * Every owner check — on a read path or a write path — goes through [permits] rather than
 * comparing `client_id` inline. Centralizing here is what lets the deferred repository-layer
 * enforcement (see the design doc, §8) drop in as a mechanical swap instead of a re-audit of
 * every call site.
 *
 * Read and write scopes are resolved separately — [readScope] and [writeScope]. Both are
 * unscoped for [PrincipalKind.OPS] (operating-company staff physically handle every goods
 * owner's stock) and owner-scoped for [PrincipalKind.OWNER] (a goods owner sees and touches
 * only its own rows, on reads and on writes alike).
 *
 * They are kept as two functions rather than one because the *reasons* differ and are expected
 * to diverge: read scoping is what makes client read-only portals safe, while write scoping is
 * what keeps one customer from mutating another's goods. A caller must pick the one matching
 * its operation.
 */
sealed interface TenantScope {

    /** All owners visible — an ops principal. */
    data object Unscoped : TenantScope

    /** Exactly one owner visible — a goods-owner principal. */
    data class Owner(val clientId: Long) : TenantScope

    fun permits(rowClientId: Long): Boolean = when (this) {
        Unscoped -> true
        is Owner -> rowClientId == clientId
    }
}

/** Read scope for this request's principal. */
fun TenantContext.readScope(): TenantScope = when (principalKind) {
    PrincipalKind.OPS -> TenantScope.Unscoped
    PrincipalKind.OWNER -> TenantScope.Owner(clientId)
}

/**
 * Scope for mutation paths.
 *
 * Owner-blind for [PrincipalKind.OPS] only: operating-company staff physically handle every
 * goods owner's stock, so a picker on a mixed route must be able to mutate any owner's rows.
 * A goods-owner principal stays scoped to its own rows — it has no business mutating another
 * customer's stock, and because mutation endpoints echo the mutated row back, an unscoped
 * write would also be an unscoped read.
 */
fun TenantContext.writeScope(): TenantScope = when (principalKind) {
    PrincipalKind.OPS -> TenantScope.Unscoped
    PrincipalKind.OWNER -> TenantScope.Owner(clientId)
}

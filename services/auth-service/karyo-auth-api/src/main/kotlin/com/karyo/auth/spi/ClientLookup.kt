package com.karyo.auth.spi

/**
 * In-process goods-owner lookup, consumed by other modules instead of a cross-module
 * repository dependency. Mirrors `ProductLookup` / `ShipmentLookup`.
 *
 * This is a cross-module read contract, not an ADR-036 strategy seam: there is no plausible
 * second implementation, and none should be added.
 */
interface ClientLookup {
    /**
     * Batch id -> client name. Unknown ids (never created, or belonging to another tenant)
     * are simply absent from the map — callers fall back to rendering the raw id rather than
     * fabricating a name. One query per call, never one per id.
     */
    fun findNamesByIds(ids: Set<Long>): Map<Long, String>

    /** Whether a client row exists, for validating a client id supplied by a caller. */
    fun exists(id: Long): Boolean

    /**
     * Whether a client row exists AND is ACTIVE. Stronger than [exists]: use it where the
     * client must be a live counterparty (e.g. the target of a changeClient reassignment);
     * keep [exists] where a retired client is still a valid referent (e.g. its historical
     * document templates). Deliberately unscoped, like [exists] — the production caller is
     * an OPS-only write path.
     */
    fun isActive(id: Long): Boolean
}

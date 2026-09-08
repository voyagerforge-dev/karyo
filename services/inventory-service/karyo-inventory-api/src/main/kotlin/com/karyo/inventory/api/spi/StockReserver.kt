package com.karyo.inventory.api.spi

import java.math.BigDecimal

/**
 * Inputs for a stock reservation — mirrors the selection knobs so the caller (orders) drives
 * completeHandling/preferMatching/enforceLot/lot through reservation (ADR-036 strategy flow).
 */
data class ReservationRequest(
    val itemDataId: Long,
    val amount: BigDecimal,
    val lotNumber: String? = null,
    val useLockedStock: Boolean = false,
    val preferComplete: Boolean = true,
    val preferMatching: Boolean = false,
    val completeHandling: Int = 0,
    val enforceLot: Boolean = false,
    /** Audit correlation written to the InventoryJournal (e.g. the order number) so a reservation is
     *  traceable to its cause; defaults to a generic tag for callers without a business id. */
    val correlationId: String = "stock-reserver",
    /** Stock units to exclude from selection (e.g. a just-short source on a follow-up re-pick). */
    val excludeStockUnitIds: List<Long> = emptyList(),
)

/**
 * In-process stock reservation contract. Implemented by inventory-core and consumed
 * by other modules (e.g. the orders module at order release) instead of a
 * cross-service REST client. Selection is delegated to the existing 13-pass FIFO
 * algorithm; the `reservedAmount` bookkeeping reuses the existing inventory math.
 */
interface StockReserver {

    /**
     * Selects + reserves stock for [request]; never throws on shortage
     * (see [ReservationOutcome.shortfall]).
     */
    fun reserve(request: ReservationRequest): ReservationOutcome

    /**
     * Explicit-`clientId` variant of [reserve] for trusted in-process SPI callers whose ambient
     * `TenantContext` cannot be relied on to already carry the right tenant -- same doctrine as
     * [reserveOnStockUnit]'s explicit-`clientId` overload (a cross-module port invoked directly,
     * bypassing REST/`TenantFilter`, sees the request scope active but UNPRIMED, ambient
     * `clientId` defaulting to 0). Selection is scoped by [clientId], never the ambient
     * `TenantContext`.
     */
    fun reserve(request: ReservationRequest, clientId: Long): ReservationOutcome

    /**
     * Releases previously made reservations (decrements `reservedAmount`).
     * Idempotent and defensive: a stock unit that no longer exists is skipped
     * with a log entry, and `reservedAmount` never goes below zero.
     */
    fun release(reservations: List<ReservedStock>)

    /**
     * Explicit-`clientId` variant of [release] for the bulk list form -- same doctrine as the
     * single-slice explicit [release] overload below, applied to a whole reservation batch: a
     * stock unit that does not resolve under [clientId] (gone, or genuinely owned by a different
     * tenant) is treated the same defensive way the ambient [release] already treats a vanished
     * unit -- skipped with a log entry, never a partial cross-tenant write.
     */
    fun release(reservations: List<ReservedStock>, clientId: Long)

    /**
     * Targeted single-stock-unit reserve — no 13-pass selection, no fallback to another unit.
     * For callers that already know the EXACT stock unit a slice must bind to (e.g. fulfillment's
     * pick top-up recovery path, which must not re-select onto a different unit than the stale
     * order-side bookkeeping still names) and need the same reservation invariant [reserve] gives
     * its callers: "if this returns true, `reservedAmount` on [stockUnitId] genuinely reflects
     * [amount] more than before."
     *
     * Returns `false` (no partial reserve, no throw across the module boundary) when the unit is
     * locked or `availableAmount < amount` — mirrors [reserve]'s existing internal
     * catch-and-skip-per-unit idiom rather than propagating an inventory-internal exception type
     * to callers in other modules.
     */
    fun reserveOnStockUnit(stockUnitId: Long, amount: BigDecimal, correlationId: String = "stock-reserver"): Boolean

    /**
     * Explicit-`clientId` variant of [reserveOnStockUnit] for trusted in-process SPI callers whose
     * ambient `TenantContext` cannot be relied on to already carry the right tenant -- e.g. a
     * cross-module port invoked from a `@Scheduled` sweep, where the request scope is active but
     * UNPRIMED (ambient `clientId` defaults to 0). Scoped by STRICT `clientId` equality against the
     * passed [clientId], never the ambient `TenantContext`/`principalKind` (mirrors the
     * explicit-`clientId` overload doctrine on `StockUnitLookup`/`UnitLoadLookup`).
     *
     * Distinguishes the two ways a targeted reserve can fail to find its unit: a stock unit that
     * genuinely does not exist returns `false` (same "can't reserve this one" idiom as
     * [reserveOnStockUnit]); a stock unit that EXISTS but belongs to a different client throws
     * [com.karyo.inventory.exception.InventoryException.Forbidden] rather than being silently
     * treated as "gone" -- collapsing a tenant-scope violation into an ordinary false would let a
     * caller's own bug (wrong `clientId`, unprimed context) look like a normal missed reservation
     * instead of surfacing as the availability-leak bug it actually is.
     */
    fun reserveOnStockUnit(stockUnitId: Long, amount: BigDecimal, clientId: Long, correlationId: String = "stock-reserver"): Boolean

    /**
     * Explicit-`clientId` variant of [release] for a single named slice -- same doctrine as the
     * [reserveOnStockUnit] explicit-`clientId` overload above: strict `clientId` equality, a
     * genuinely absent stock unit is a defensive no-op (mirrors [release]'s existing idiom), but a
     * `clientId` mismatch against an EXISTING stock unit throws
     * [com.karyo.inventory.exception.InventoryException.Forbidden] rather than being swallowed as
     * "unit gone" -- callers (e.g. `CrossDockOrdersPort.releaseSlice`) must perform this call BEFORE
     * deleting their own bookkeeping row, so a thrown [com.karyo.inventory.exception.InventoryException.Forbidden]
     * rolls back the whole caller transaction instead of leaving stock-side `reservedAmount`
     * permanently stranded.
     */
    fun release(stockUnitId: Long, amount: BigDecimal, clientId: Long, correlationId: String = "stock-reserver")
}

/** One reservation slice: [amount] reserved on stock unit [stockUnitId]. */
data class ReservedStock(
    val stockUnitId: Long,
    val amount: BigDecimal,
)

/**
 * Result of a [StockReserver.reserve] call. [shortfall] is the portion of the
 * requested amount that could not be reserved (ZERO when fully covered).
 */
data class ReservationOutcome(
    val reservations: List<ReservedStock>,
    val shortfall: BigDecimal,
)

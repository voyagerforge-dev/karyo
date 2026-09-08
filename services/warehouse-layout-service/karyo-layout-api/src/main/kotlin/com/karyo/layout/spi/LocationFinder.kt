package com.karyo.layout.spi

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * In-process putaway location-finder contract. Implemented by layout-core and consumed
 * by other modules (the tasks module's putaway flow) instead of a cross-service REST
 * client — same api-only seam as the inventory module's `StockReserver`/`StockReceiver`.
 *
 * The layout module **owns** the search: it is the only module that knows about areas,
 * zones, location types, locking, and allocation, so the finder lives here and exposes
 * a narrow request/result contract to callers.
 *
 * **Reservation semantics:** [findPutawayLocation] does not just *suggest* a location,
 * it **soft-reserves** it. A successful [LocationFinderResult.Found] writes a
 * short-lived `LocationReservation` row (default TTL 10 min) keyed by the caller's
 * `transportOrderId`, and that reservation counts toward the location's effective
 * allocation. This prevents two concurrent putaways from being handed the same nearly
 * full location. The caller MUST eventually [releaseReservation] (on task
 * completion/cancel); expired reservations are also swept automatically.
 */
interface LocationFinder {

    /**
     * Finds (and soft-reserves) the best putaway location for a unit load.
     *
     * Returns [LocationFinderResult.Found] with the chosen location when one survives
     * all filters, or [LocationFinderResult.NoLocation] (with a human-readable reason
     * naming the constraint that emptied the candidate set) when none does. Never
     * throws on "no location" — that is a valid, expected result the caller surfaces
     * to the operator.
     */
    fun findPutawayLocation(request: LocationFinderRequest): LocationFinderResult

    /**
     * Releases the soft reservation held for [transportOrderId] (idempotent — a no-op
     * when none exists). Called on putaway task completion or cancellation so the
     * location's effective allocation reflects reality again.
     */
    fun releaseReservation(transportOrderId: Long)

    /**
     * PT15: directly (soft-)reserves [locationId] for [transportOrderId] without running a
     * search — the narrow write half of [findPutawayLocation]'s reservation semantics,
     * exposed standalone for the tasks module's transfer-chain successor creation
     * ([com.karyo.tasks.service.ChainContinuationService]), which already knows the exact
     * location (the predecessor's real final target) the new order should hold. Same
     * TTL/percent semantics as the reservation a [findPutawayLocation] call would have
     * written for that location. Always additive — never checks capacity — so pair it with
     * a same-transaction [releaseReservation] of whatever reservation it's replacing (the
     * chain re-key pattern), not with a fresh search.
     */
    fun reserve(locationId: Long, transportOrderId: Long)

    /**
     * Consolidation search: a location whose EXISTING same-item stock the incoming stock can be
     * added to. Public behavioral contract:
     * `docs/functional/location-finder.md#2a-the-add-to-location-search-mode-lf10`. Priority 1: the item's
     * fix assignments in order (location unlocked, PICKING area, every same-item stock there
     * shares the requested lot, none vetoed). Priority 2: FIFO-ordered pickable same-item stock
     * matching lot/bestBefore (each veto skipped, first survivor on an unlocked PICKING location
     * wins).
     *
     * ADVISORY: unlike [findPutawayLocation] this mode does NOT soft-reserve (hence its own
     * result type; [LocationFinderResult.Found] implies a reservation, this does not).
     * Consolidation targets occupied stock, which cannot be double-booked the way an emptying
     * slot can; a caller needing exclusivity calls [reserve] explicitly. Karyo-shape divergence,
     * recorded: the request takes attributes (item/lot/bestBefore/vetoes), not a source
     * StockUnit; legacy's source-must-be-ON_STOCK guard is the caller's business.
     * strategy.manualSearch short-circuits to None before any query, exactly as in putaway.
     */
    fun findAddToLocation(request: AddToLocationRequest): AddToLocationResult
}

/**
 * One consolidation search request (LF10, location-finder sprint).
 *
 * Karyo-shape divergence, recorded (A7): legacy's signature takes a source `StockUnit` and
 * guards `state != ON_STOCK -> null`. Karyo's request takes the attributes directly ([itemDataId],
 * [lotNumber], [bestBefore], plus [vetoStockUnitIds]) because the finder must not fetch a
 * foreign module's entity to unpack it; the ON_STOCK-source guard is the caller's business.
 *
 * @param clientId goods owner (domain owner, silo meaning); consolidation searches only the
 *        requester's own stock, unlike [LocationFinder.findAddToLocation]'s putaway sibling.
 * @param itemDataId the incoming stock's product (foreign-module id, no FK).
 * @param lotNumber the incoming stock's lot, compared for equality against existing stock at a
 *        candidate fix location -- `null` (lot-less) only matches lot-less stock there, never a
 *        wildcard.
 * @param bestBefore the incoming stock's best-before, narrows the priority-2 FIFO query only
 *        when non-null.
 * @param vetoStockUnitIds stock units the caller has already ruled out (e.g. the incoming
 *        stock's own source unit); any candidate referencing one is disqualified.
 * @param storageStrategyId optional layout strategy driving the `manualSearch` bypass; null
 *        falls back to the product's default strategy, same resolution chain as putaway.
 */
data class AddToLocationRequest(
    val clientId: Long,
    val itemDataId: Long,
    val lotNumber: String? = null,
    val bestBefore: LocalDate? = null,
    val vetoStockUnitIds: Set<Long> = emptySet(),
    val storageStrategyId: Long? = null,
)

/** Outcome of a [LocationFinder.findAddToLocation] call. */
sealed class AddToLocationResult {
    /**
     * A consolidation target was found. [viaFixAssignment] distinguishes the two priorities for
     * callers and tests -- the corpus treats them as one return, but the fact is free and
     * diagnostic. ADVISORY: no [LocationReservation][com.karyo.layout.domain.model.LocationReservation]
     * is written for this result, unlike [LocationFinderResult.Found].
     */
    data class Found(
        val locationId: Long,
        val locationName: String,
        val viaFixAssignment: Boolean,
    ) : AddToLocationResult()

    /** No consolidation target qualified. [reason] names why (no fix/FIFO candidate survived,
     * or `manualSearch` short-circuited the search). */
    data class None(
        val reason: String,
    ) : AddToLocationResult()
}

/**
 * One putaway location request.
 *
 * @param unitLoadId the unit load being put away (carried through for audit; the finder
 *        reasons over [weight]/[clientId]).
 * @param unitLoadTypeId the unit load's type — used by filter 7 (UL-type compatibility).
 * @param weight unit-load weight for the lifting-capacity filter (filter 5); ZERO when
 *        unknown (treated as "fits anywhere").
 * @param clientId goods owner (domain owner, silo meaning) for ownership/mixing filters
 *        (6, 8); null ⇒ shared/unscoped putaway.
 * @param reservationKey the caller's correlation id for the soft reservation — the
 *        tasks module passes the **transport order id** so it can later
 *        [LocationFinder.releaseReservation] by the same key. The finder writes the
 *        `LocationReservation` row keyed by this value.
 * @param storageStrategyId optional layout strategy driving zone preference and
 *        client-mixing; null ⇒ the system default behavior.
 * @param preferredZoneId explicit zone constraint (overrides the strategy zone when set).
 * @param pickingOnly when true the finder targets PICKING areas instead of STORAGE
 *        (replenishment-style putaway); v1.2 putaway always passes false.
 * @param itemDataId the incoming stock's product (foreign-module id, no FK) — drives the
 *        `useAreaStrategyDate`/`useItemDataArea` [StorageArea][com.karyo.layout.domain.model.StorageArea]
 *        area logic (locations-layout sprint Task 3). `null` when the caller doesn't know it
 *        (or the unit load carries no/mixed stock) — area restriction (a) still applies, but
 *        the product-scoped hiding rules (b)/(c) are skipped (honest degradation, not an error).
 * @param strategyDate the incoming stock's FIFO strategy date, for cross-area FIFO comparison
 *        under `useAreaStrategyDate`. `null` disables that comparison (see [itemDataId]).
 */
data class LocationFinderRequest(
    val unitLoadId: Long,
    val unitLoadTypeId: Long?,
    val weight: BigDecimal,
    val clientId: Long?,
    val reservationKey: Long,
    val storageStrategyId: Long? = null,
    val preferredZoneId: Long? = null,
    val pickingOnly: Boolean = false,
    val itemDataId: Long? = null,
    val strategyDate: Instant? = null,
)

/** Outcome of a [LocationFinder.findPutawayLocation] call. */
sealed class LocationFinderResult {
    /** A location was found and soft-reserved. */
    data class Found(
        val locationId: Long,
        val locationName: String,
    ) : LocationFinderResult()

    /**
     * No location qualified. [reason] names the constraint that emptied the candidate
     * set (e.g. "no unlocked STORAGE location with free capacity") — useful for the
     * operator and, later, the copilot explaining *why* nothing was suggested.
     */
    data class NoLocation(
        val reason: String,
    ) : LocationFinderResult()
}

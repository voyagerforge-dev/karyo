package com.karyo.layout.spi

/**
 * SPI seam letting an extension **fully replace** the putaway finder.
 *
 * All discovered [PutawayLocationStrategy] beans are tried in ascending [priority]
 * order (lower first); the **first non-null** result wins and short-circuits the rest.
 * The built-in finder ([com.karyo.layout.service] `LocationFinderService`) registers
 * itself at the **lowest priority** ([DEFAULT_BUILTIN_PRIORITY]) so it always runs last
 * — a custom strategy at any lower priority pre-empts it. Returning null means "I have
 * no opinion, fall through to the next strategy".
 *
 * Unlike [LocationFilter] (which prunes/reorders the built-in's candidate set), a
 * strategy bypasses the built-in pipeline entirely — use it when the deployment needs a
 * wholly different putaway algorithm (e.g. slotting-optimized or WCS-driven placement).
 *
 * A strategy that returns a [LocationFinderResult.Found] is responsible for whatever
 * reservation semantics it wants; the built-in strategy creates the standard
 * `LocationReservation`. The public [LocationFinder] facade calls the strategy chain
 * and applies the built-in reservation only when the built-in strategy answered.
 */
interface PutawayLocationStrategy {

    /**
     * Returns a [LocationFinderResult] to use for this [request], or null to defer to
     * the next strategy in priority order. (Named distinctly from
     * [LocationFinder.findPutawayLocation] so one class can implement both the public
     * facade and the built-in strategy.)
     */
    fun tryFindPutawayLocation(request: LocationFinderRequest): LocationFinderResult?

    /** Lower runs first; the built-in strategy uses [DEFAULT_BUILTIN_PRIORITY] (runs last). */
    fun priority(): Int = DEFAULT_BUILTIN_PRIORITY

    companion object {
        /** Lowest priority — the built-in finder; custom strategies use lower values to pre-empt. */
        const val DEFAULT_BUILTIN_PRIORITY = Int.MAX_VALUE
    }
}

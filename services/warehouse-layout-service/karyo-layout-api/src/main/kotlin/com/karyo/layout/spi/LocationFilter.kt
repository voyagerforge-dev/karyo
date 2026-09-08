package com.karyo.layout.spi

/**
 * SPI seam for post-filtering and **reordering** putaway location candidates.
 *
 * After the built-in finder has applied its query predicates and produced an ordered
 * candidate list, every discovered [LocationFilter] is applied in ascending
 * [priority] order (lower first). Each filter receives the current candidate list and
 * returns the list to carry forward — it may drop candidates (veto) **and/or reorder
 * them**.
 *
 * **CRITICAL — the finder HONORS the order returned by filters.** This is the lesson
 * carried over from the stock-selection FSD finding (where the inventory selector
 * silently re-imposed FIFO and discarded SPI reordering — divergence #9 in
 * `docs/functional/stock-selection.md`). The putaway finder does **NOT** re-sort after
 * applying filters: whatever order the last filter returns is the order the finder
 * picks from (it takes `first()`). A filter that wants finder order preserved must sort
 * by [LocationCandidate.ordinal] itself; a filter that returns a reordered list gets
 * exactly that ranking. Do not add a post-filter sort to the finder.
 *
 * Discovered as CDI beans from any JAR on the classpath. Deploy a client policy in an
 * extension JAR compiled against the `api` module only.
 */
interface LocationFilter {

    /**
     * Returns the candidates to keep, in the order they should be considered. May be a
     * subset and/or a reordering of [candidates]. Returning an empty list vetoes all
     * remaining candidates (the finder then reports NoLocation).
     */
    fun apply(candidates: List<LocationCandidate>, context: LocationFinderRequest): List<LocationCandidate>

    /** Lower runs first. Default 1000. */
    fun priority(): Int = DEFAULT_PRIORITY

    companion object {
        const val DEFAULT_PRIORITY = 1000
    }
}

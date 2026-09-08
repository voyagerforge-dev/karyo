package com.karyo.layout.spi

import java.math.BigDecimal

/**
 * A surviving putaway location candidate, as seen by [LocationFilter] extensions.
 *
 * This is a flat, api-only projection of a storage location (no JPA entity leaks across
 * the module boundary) carrying just what a post-filter needs to rank or veto:
 * identity, the *effective* allocation (base allocation + active soft reservations,
 * 0–100), the zone/area/type ids, and the goods owner currently associated.
 *
 * @param effectiveAllocation base location allocation plus active reservation load,
 *        already computed by the finder (filter 3's emptiness gate uses the same value).
 * @param ordinal the finder's pre-SPI ordering position (0 = emptiest/first). A filter
 *        that wants to *preserve* finder order can sort by this; a filter that returns
 *        a reordered list is honored verbatim (see [LocationFilter]).
 */
data class LocationCandidate(
    val locationId: Long,
    val locationName: String,
    val zoneId: Long?,
    val areaId: Long,
    val locationTypeId: Long,
    val effectiveAllocation: BigDecimal,
    val clientId: Long,
    val ordinal: Int,
)

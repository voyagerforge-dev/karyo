package com.karyo.layout.spi

/**
 * In-process lookup for a PACK_STAGING location, where picked goods are staged on a
 * pick container before packing. Implemented by layout-core and consumed by the
 * fulfillment module (e.g. when releasing a pick container to a staging spot).
 *
 * Returns null when no PACK_STAGING area/location is configured for the tenant.
 */
interface StagingLocationLookup {
    fun findPackStaging(clientId: Long): StagingLocation?

    /** First location in any area whose usages contain SHIP_STAGING (the ship dock). Null if unconfigured. */
    fun findShipStaging(clientId: Long): StagingLocation?
}

/** Flat, api-only projection of a staging location (no JPA entity leaks across the boundary). */
data class StagingLocation(
    val id: Long,
    val name: String,
)

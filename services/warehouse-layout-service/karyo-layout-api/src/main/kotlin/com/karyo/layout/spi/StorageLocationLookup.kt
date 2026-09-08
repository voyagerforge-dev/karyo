package com.karyo.layout.spi

/**
 * Read seam for resolving a storage location by id, for callers outside the layout module that
 * need to validate a location reference at write time. Row 10's delivery order destination is the
 * first consumer; row 8's OrderStrategy.defaultDestination is the second.
 *
 * [clientId] is an explicit parameter rather than an ambient TenantContext read, per the standing
 * doctrine, so the seam stays safe for a scheduled caller.
 */
interface StorageLocationLookup {
    /** The location named by [locationId], scoped to [clientId] by strict equality, or null if unknown/foreign. */
    fun findById(locationId: Long, clientId: Long): LocationRef?

    /**
     * Batch id -> location name lookup, shaped like [com.karyo.product.spi.ProductLookup.findNamesByIds]
     * -- backs a page-level display read (e.g. `DeliveryOrderResponse.destinationLocationName`
     * across a whole page of orders) without an N+1. Scoped to [clientId] by strict equality,
     * same convention as [findById]; missing ids (unknown or foreign-tenant) are simply absent
     * from the result map -- callers treat absence as an honest gap, never fabricate a name.
     */
    fun findNamesByIds(ids: Set<Long>, clientId: Long): Map<Long, String>

    /**
     * Batch id -> zone name lookup (wave bulk fulfillment sprint, Task 8): backs
     * [com.karyo.fulfillment.spi.PickZoneLookup]'s real (wave-module) implementation, which
     * resolves a batch PickOrder's `batchZone` from the storage location its source stock sits
     * on. Scoped to [clientId] by strict equality, same convention as [findById]/[findNamesByIds].
     * A location that exists but carries no `zone_id` maps to `null` (present in the result map,
     * value null); a location id not found (unknown or foreign-tenant) is simply absent from the
     * map -- same "absence is an honest gap" convention as [findNamesByIds].
     */
    fun zoneNamesByLocationIds(locationIds: Set<Long>, clientId: Long): Map<Long, String?>
}

/** Minimal cross-module read shape of a [com.karyo.layout.domain.model.StorageLocation]. */
data class LocationRef(val id: Long, val name: String)

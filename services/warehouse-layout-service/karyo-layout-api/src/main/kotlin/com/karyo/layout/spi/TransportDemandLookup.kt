package com.karyo.layout.spi

/**
 * In-process cross-module READ contract, like [StorageLocationLookup] and
 * [LocationLockPort], declared here in layout-api and implemented by the tasks module
 * (the [com.karyo.inventory.api.spi.OpenPickGuard] direction: layout declares the
 * contract, tasks-core implements it, zero new Gradle edges since tasks-core already
 * depends on layout-api).
 *
 * Backs the location finder's client-mixing and item-mixing passes, specified in
 * `docs/functional/location-finder.md#2-the-built-in-filter-passes--as-implemented`. A candidate
 * location with no live stock can still have OTHER owners'
 * or OTHER items' stock already heading toward it via an open transport order, and the
 * finder must see that in-flight demand to avoid picking a location that will collide
 * the moment the transport lands.
 */
interface TransportDemandLookup {
    /**
     * All in-flight transport demand targeting the given locations, one row per open
     * transport order: (target location id, goods-owner clientId, itemDataId when known,
     * the order's own id). "In flight" means state not in (FINISHED, CANCELED), the tasks
     * module's standing convention for open work.
     *
     * The target location id is COALESCED (defect-burndown-5, row :1614): destination
     * location id when the order has one (MOVE/REPLENISH, stamped at creation), else the
     * suggested location id (open PUTAWAY/TRANSFER -- destinationLocationId is only stamped
     * at completion). A row with neither set is skipped entirely.
     *
     * Deliberately UNSCOPED (no tenant filter): the location finder's mixing passes must see
     * other owners' in-flight demand to answer "is someone else's stock heading here". Consumed
     * in-process by the finder only; never expose through REST. The OpenPickGuard direction:
     * layout declares the contract, tasks-core implements it, zero new Gradle edges.
     *
     * The order id is carried so [com.karyo.layout.service.OccupancyMixReader] can
     * self-exclude: a caller re-resolving its OWN order's suggestion must not see that
     * order's own prior demand row as someone else's in-flight demand and exclude the very
     * location it is trying to re-resolve.
     */
    fun openDemandByLocationIds(destinationLocationIds: Set<Long>): List<TransportDemandRef>
}

data class TransportDemandRef(
    val locationId: Long,
    val clientId: Long,
    val itemDataId: Long?,
    val transportOrderId: Long,
)

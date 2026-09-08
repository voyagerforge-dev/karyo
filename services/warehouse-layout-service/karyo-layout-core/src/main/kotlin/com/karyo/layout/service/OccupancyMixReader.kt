package com.karyo.layout.service

import com.karyo.inventory.api.spi.LocationOccupantRef
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.spi.TransportDemandLookup
import com.karyo.layout.spi.TransportDemandRef
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Computes the [LocationFinderService] built-in occupancy-mixing passes (location-finder
 * sprint, LF9/LF8, 2026-08-16, Task 5). Public behavioral contract:
 * `docs/functional/location-finder.md#2-the-built-in-filter-passes--as-implemented`. Split out of
 * [LocationFinderService] purely to keep that class's methods
 * within the project's Detekt size limits, same rationale as [AreaOccupancyReader] and
 * [GroupCapacityReader].
 *
 * **Two deliberately unscoped sources, each read at most ONCE per invocation for the whole
 * candidate set (never per candidate):** [StockUnitLookup.occupantsByLocationIds] (live
 * stock occupants; window is `state < DELETABLE(1000)`) and
 * [TransportDemandLookup.openDemandByLocationIds] (in-flight transport demand, via the
 * optional [Instance] -- an empty `Instance` means zero demand, zero calls). When neither the
 * client-mixing nor item-mixing check is active, this returns the empty set without making
 * either call at all.
 *
 * **SHIPPED-ghost occupants closed (defect-burndown-5, adjudication A1):** the older revision
 * of this KDoc warned that a terminal-but-never-purged SHIPPED row could linger as an occupant
 * indefinitely (the reaper that would otherwise clean it up is opt-in and default-off). A1
 * makes [com.karyo.inventory.service.DefaultStockPicker.shipContainer] promote every shipped
 * stock unit straight to DELETABLE(1000) in the same transaction, so a shipped row drops out of
 * `occupantsByLocationIds`'s `state < DELETABLE` window immediately at ship time, not only once
 * the (still opt-in) reaper eventually purges it. The gap does not depend on reaper posture
 * anymore.
 *
 * **Self-exclusion guard (defect-burndown-5, row :1614):** [TransportDemandLookup] now also
 * surfaces an open PUTAWAY/TRANSFER's own suggested target as demand (previously invisible --
 * see that interface's KDoc). That means a caller re-resolving its OWN order's suggestion
 * (`TaskService.reResolveSuggestion`, which passes the order's own id as
 * [com.karyo.layout.spi.LocationFinderRequest.reservationKey]) could in principle see its own
 * prior demand row in the batched [TransportDemandLookup.openDemandByLocationIds] result.
 * [blockedLocationIds] takes `requestingOrderId` and its demand filter skips any ref whose
 * `transportOrderId` matches it, so a re-resolving order never excludes the location it is
 * itself trying to re-resolve. Demand from every OTHER order still blocks normally.
 *
 * **Defensive, not a live-bug fix:** with today's `TaskService` callers this path is currently
 * unreachable -- both finder call sites (`createPutawayTask`, and `start` via
 * `reResolveSuggestion`) only run while `order.suggestedLocationId` is still null, so the
 * order's own row cannot yet exist as demand at the moment it asks the finder for a location.
 * The guard exists for a future caller or a changed re-resolve gate that might call the finder
 * once a suggestion already exists, not because it closes an observed defect today.
 */
@ApplicationScoped
class OccupancyMixReader(
    private val stockUnitLookup: StockUnitLookup,
    private val transportDemandLookup: Instance<TransportDemandLookup>,
) {

    /**
     * Locations (from [locationIds]) that must be EXCLUDED by the mixing passes.
     *
     * `clientCheck` is active when `mixClient` is false AND [requestClientId] is known;
     * `itemCheck` is active when `mixItem` is false AND [requestItemDataId] is known. A
     * location is blocked if EITHER active check finds a mismatching occupant or in-flight
     * demand row -- the two checks are independent, not "both must mismatch".
     *
     * [requestingOrderId] is the caller's own transport order id (see
     * [com.karyo.layout.spi.LocationFinderRequest.reservationKey], which the tasks module --
     * today's only caller -- always populates with it): the self-exclusion guard. Any demand
     * row whose `transportOrderId` equals it is skipped, never contributing a block.
     */
    fun blockedLocationIds(
        locationIds: Set<Long>,
        requestClientId: Long?,
        requestItemDataId: Long?,
        mixClient: Boolean,
        mixItem: Boolean,
        requestingOrderId: Long,
    ): Set<Long> {
        val clientCheck = !mixClient && requestClientId != null
        val itemCheck = !mixItem && requestItemDataId != null
        if ((!clientCheck && !itemCheck) || locationIds.isEmpty()) return emptySet()

        val occupants = stockUnitLookup.occupantsByLocationIds(locationIds)
        val demand = transportDemandLookup.stream().findFirst()
            .map { it.openDemandByLocationIds(locationIds) }
            .orElse(emptyList())

        val blocked = HashSet<Long>()
        blocked += blockedByOccupants(occupants, requestClientId, requestItemDataId, clientCheck, itemCheck)
        blocked += blockedByDemand(demand, requestClientId, requestItemDataId, clientCheck, itemCheck, requestingOrderId)
        return blocked
    }

    private fun blockedByOccupants(
        occupants: List<LocationOccupantRef>,
        requestClientId: Long?,
        requestItemDataId: Long?,
        clientCheck: Boolean,
        itemCheck: Boolean,
    ): Set<Long> {
        val blocked = HashSet<Long>()
        occupants.forEach { o ->
            if (clientCheck && o.clientId != requestClientId) blocked += o.locationId
            if (itemCheck && o.itemDataId != requestItemDataId) blocked += o.locationId
        }
        return blocked
    }

    private fun blockedByDemand(
        demand: List<TransportDemandRef>,
        requestClientId: Long?,
        requestItemDataId: Long?,
        clientCheck: Boolean,
        itemCheck: Boolean,
        requestingOrderId: Long,
    ): Set<Long> {
        val blocked = HashSet<Long>()
        demand.forEach { d ->
            if (d.transportOrderId == requestingOrderId) return@forEach
            if (clientCheck && d.clientId != requestClientId) blocked += d.locationId
            if (itemCheck && d.itemDataId != null && d.itemDataId != requestItemDataId) blocked += d.locationId
        }
        return blocked
    }
}

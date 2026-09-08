package com.karyo.layout.service

import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.AddToLocationRequest
import com.karyo.layout.spi.AddToLocationResult
import jakarta.enterprise.context.ApplicationScoped

/**
 * LF10 consolidation search algorithm (location-finder sprint Task 7). Public behavioral
 * contract: `docs/functional/location-finder.md#2a-the-add-to-location-search-mode-lf10`. A
 * collaborator bean of
 * [LocationFinderService] rather than folded into it (A6): the algorithm is a self-contained
 * unit over its own two data sources (fix assignments, and the two [StockUnitLookup]
 * consolidation reads from Task 6) with none of the putaway search's allocation/capacity/zone
 * machinery.
 *
 * **Priority 1 -- fixed picking location.** [FixAssignmentRepository.findByItemDataId], ordered
 * `orderIndex` then id (fix iteration order). For each fix, in order: the location must be
 * unlocked AND its area usage must contain PICKING (batched via ONE
 * [StorageLocationRepository.findUnlockedPickingIds] call over every fix location, never
 * per-candidate); every same-item stock already at that location must share the request's
 * [AddToLocationRequest.lotNumber] (including `null` -- a lot-less request only consolidates
 * onto lot-less stock, matching the null-equality contract in the public specification); none
 * of that stock may be in
 * [AddToLocationRequest.vetoStockUnitIds]. The first fix location surviving all three checks
 * wins with `viaFixAssignment = true`.
 *
 * **Priority 2 -- FIFO-ordered pickable stock.** [StockUnitLookup.fifoConsolidationRefs] returns
 * same-item stock matching lot/bestBefore, FIFO-ordered, capped at [FIFO_LIMIT]. Walked in
 * order: a vetoed stock unit is skipped; a ref whose location is not in the SAME
 * [StorageLocationRepository.findUnlockedPickingIds] batched set is skipped (the FIFO refs are
 * NOT pre-filtered for lock/PICKING -- inventory cannot see those layout facts). The first
 * survivor wins with `viaFixAssignment = false`.
 *
 * No location is ever soft-reserved here -- see [com.karyo.layout.spi.LocationFinder
 * .findAddToLocation]'s KDoc for the advisory-mode rationale.
 *
 * **Batching:** at most one [FixAssignmentRepository.findByItemDataId] query, up to two
 * [StorageLocationRepository.findUnlockedPickingIds] queries (fix-locations set, then
 * FIFO-refs set -- only reached if priority 1 exhausts), and up to two [StockUnitLookup] calls
 * ([StockUnitLookup.itemStocksByLocationIds] only when fixes exist,
 * [StockUnitLookup.fifoConsolidationRefs] only if priority 1 exhausts) -- never per-candidate.
 */
@ApplicationScoped
class AddToLocationFinder(
    private val fixAssignmentRepository: FixAssignmentRepository,
    private val stockUnitLookup: StockUnitLookup,
    private val locationRepository: StorageLocationRepository,
) {

    /** Runs the two-priority search described in the class KDoc. Never throws on "no
     * candidate" -- [AddToLocationResult.None] is the expected, caller-surfaced outcome. */
    fun search(request: AddToLocationRequest): AddToLocationResult =
        searchFixAssignments(request) ?: searchFifoCandidates(request)

    /**
     * Priority 1. Returns `null` (not [AddToLocationResult.None]) when no fix qualifies, so the
     * caller falls through to priority 2 -- `null` here means "keep looking", not "give up".
     */
    private fun searchFixAssignments(request: AddToLocationRequest): AddToLocationResult.Found? {
        val fixes = fixAssignmentRepository.findByItemDataId(request.itemDataId, request.clientId)
            .sortedWith(compareBy({ it.orderIndex }, { it.id }))
        if (fixes.isEmpty()) return null

        val fixLocationIds = fixes.mapNotNull { it.location.id }.toSet()
        val qualifyingFixIds = locationRepository.findUnlockedPickingIds(fixLocationIds)
        val stocksByLocation = stockUnitLookup
            .itemStocksByLocationIds(request.itemDataId, fixLocationIds, request.clientId)
            .groupBy { it.locationId }

        for (fix in fixes) {
            val locationId = fix.location.id ?: continue
            val stocks = stocksByLocation[locationId] ?: emptyList()
            val qualifies = locationId in qualifyingFixIds &&
                stocks.none { it.lotNumber != request.lotNumber } &&
                stocks.none { it.stockUnitId in request.vetoStockUnitIds }
            if (qualifies) {
                return AddToLocationResult.Found(locationId, fix.location.name, viaFixAssignment = true)
            }
        }
        return null
    }

    /** Priority 2, always terminal -- returns [AddToLocationResult.None] when nothing survives. */
    private fun searchFifoCandidates(request: AddToLocationRequest): AddToLocationResult {
        val refs = stockUnitLookup.fifoConsolidationRefs(
            request.itemDataId, request.lotNumber, request.bestBefore, request.clientId, FIFO_LIMIT,
        )
        val qualifying = locationRepository.findUnlockedPickingIds(refs.map { it.locationId }.toSet())
        for (ref in refs) {
            if (ref.stockUnitId in request.vetoStockUnitIds) continue
            if (ref.locationId !in qualifying) continue
            return AddToLocationResult.Found(ref.locationId, ref.locationName, viaFixAssignment = false)
        }
        return AddToLocationResult.None("no consolidation target for item ${request.itemDataId}")
    }

    companion object {
        /** Pragmatic cap on how many FIFO candidates are worth walking before giving up on a
         * search -- same shape as the putaway finder's `CANDIDATE_FETCH_LIMIT`. A warehouse
         * where the first 200 FIFO stocks of one item+lot are all vetoed or all on
         * non-picking/locked locations returns [AddToLocationResult.None]. */
        private const val FIFO_LIMIT = 200
    }
}

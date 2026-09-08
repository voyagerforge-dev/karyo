package com.karyo.replenishment.service

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.inventory.api.spi.ReplenishmentSourceSelector
import com.karyo.inventory.api.spi.SourceQuery
import com.karyo.inventory.api.spi.StockSummary
import com.karyo.inventory.api.spi.StockSummaryLookup
import com.karyo.layout.spi.ItemDataAreaLookup
import com.karyo.layout.spi.ItemDataAreaView
import com.karyo.layout.spi.LocationLockPort
import com.karyo.replenishment.dto.GeneratedTask
import com.karyo.replenishment.dto.ReplenishmentShortfall
import com.karyo.tasks.spi.AreaReplenishmentTaskCommand
import com.karyo.tasks.spi.TransportOrderPort
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * R12b (Task 6): one deficient area's scan outcome -- see [AreaReplenishmentService.processArea].
 * Same shape as `ReplenishmentService`'s private `FixOutcome` for the Mode-1 (fix-face) pass.
 */
private sealed class AreaOutcome {
    data class Generated(val task: GeneratedTask) : AreaOutcome()
    data class Shortfall(val shortfall: ReplenishmentShortfall) : AreaOutcome()
}

/**
 * R12b (Task 6): sentinel [SourceQuery.targetLocationId] for the area-level scan, which has no
 * single target face to name (it targets a whole location SET via
 * [SourceQuery.excludeLocationIds] instead) -- no real [com.karyo.layout.domain.model.
 * StorageLocation] id is ever negative, so this can never accidentally exclude a real candidate.
 */
private const val NO_SINGLE_TARGET_FACE = -1L

/**
 * Area (Mode-2) replenishment scan -- split out of [ReplenishmentService] (defect burndown 4,
 * Task 4, row 10) so the correctness fixes Task 5 adds do not push that class's constructor past
 * the 10-param detekt ceiling, mirroring the `ReceiveLineValidator` precedent in
 * `karyo-orders-core` (a sibling split out of `GoodsReceiptService` for the same reason).
 * [ReplenishmentService.scanAreas] delegates here unchanged in signature and return shape, so
 * `ReplenishmentResource`/`ReplenishmentScheduler` -- both of which only ever call
 * [ReplenishmentService.scan] -- are untouched.
 *
 * **Row 8 (defect burndown 4, Task 4) -- Mode-2 tiering ruling.** Area (Mode-2) targets
 * deliberately reuse the fix-face Phase-A tiering (fix-assigned reserve slots first). The myWMS
 * corpus ordered Mode-2 sources by the StorageStrategy cluster set instead; Karyo unifies on
 * reserve-first as the designated-refill-source reading. Decided 2026-08-15 (defect burndown 4).
 */
@ApplicationScoped
class AreaReplenishmentService(
    private val itemDataAreaLookup: ItemDataAreaLookup,
    private val stockSummaryLookup: StockSummaryLookup,
    private val locationLockPort: LocationLockPort,
    private val sourceSelector: ReplenishmentSourceSelector,
    private val transportOrderPort: TransportOrderPort,
    private val strategyResolver: ReplenishmentStrategyResolver,
    private val runtimeProperties: RuntimePropertyLookup,
) {

    /**
     * R12b (Task 6) — area-level (Mode 2) replenishment scan: for every
     * [com.karyo.layout.spi.ItemDataAreaView] `clientId` has configured, tops up its
     * [com.karyo.layout.domain.model.StorageArea] from elsewhere in the warehouse when the
     * area's on-hand stock falls short of either configured threshold. Public behavioral
     * contract: `docs/functional/replenishment.md#4-area-level-replenishment`. Deficiency means
     * EITHER threshold misses its target (`plannedAmount > actual sum OR plannedStocks > actual
     * count`), not both, mirrored below as `amountDeficient || stocksDeficient`.
     *
     * **ONE order per area per pass**: a
     * deficiency that a single whole-UL move can't fully close is left for the NEXT scan pass to
     * re-evaluate against the then-current on-hand — this method never loops within one area to
     * chase full satisfaction in one call.
     *
     * [com.karyo.tasks.spi.TransportOrderPort.hasOpenAreaReplenishment] deliberately suppresses
     * a second open REPLENISH order for the same still-deficient area, matching the duplicate
     * guard Mode 1 applies through [TransportOrderPort.hasOpenReplenishment].
     *
     * **Destination - honest deterministic v1.** [com.karyo.layout.spi.LocationFinder] has no
     * area or arbitrary-location-set constraint: [com.karyo.layout.spi.LocationFinderRequest]
     * takes a `preferredZoneId`. Rather than fabricate a capacity-ranked search this codebase's
     * SPI surface does not support, [chooseDestination] picks the
     * lowest-id unlocked, non-fix-face location whose base allocation plus active soft
     * reservations is below 100 in the area's own [ItemDataAreaView.clusterLocationIds] (via
     * [LocationLockPort.lockedLocationIds] and [LocationLockPort.occupiedLocationIds], Row 4,
     * defect-burndown-4 Task 5) - arbitrary but deterministic, with no capacity ranking.
     * [com.karyo.tasks.service.TaskService.createAreaReplenishment] still soft-reserves
     * the chosen location through [com.karyo.layout.spi.LocationFinder.reserve] (the same
     * known-target reservation [com.karyo.tasks.service.ChainContinuationService] uses), so this
     * is a real allocation hold, just not a filtered or ranked search. An area with every
     * location locked, effectively full, fix-assigned (Row 2, Task 5), or zero clusters configured
     * reports a `"NO_DESTINATION"` shortfall rather than guessing.
     */
    fun scanAreas(
        clientId: Long,
        fixFaceLocationIds: Set<Long>,
        claimedUnitLoadIds: MutableSet<Long>,
    ): Pair<List<GeneratedTask>, List<ReplenishmentShortfall>> {
        val generated = mutableListOf<GeneratedTask>()
        val shortfalls = mutableListOf<ReplenishmentShortfall>()
        for (area in itemDataAreaLookup.listForReplenishment(clientId)) {
            when (val outcome = processArea(area, clientId, fixFaceLocationIds, claimedUnitLoadIds)) {
                is AreaOutcome.Generated -> generated += outcome.task
                is AreaOutcome.Shortfall -> shortfalls += outcome.shortfall
                null -> Unit // not deficient, or already has an open order -- nothing to record
            }
        }
        return generated to shortfalls
    }

    /** One area's worth of [scanAreas] — pulled out of the loop body so Detekt's
     *  `LoopWithTooManyJumpStatements` stays satisfied by construction: every early exit here is
     *  a `return` from THIS function, not a `continue` in the caller's loop, which then has zero
     *  jump statements of its own. */
    private fun processArea(
        area: ItemDataAreaView,
        clientId: Long,
        fixFaceLocationIds: Set<Long>,
        claimedUnitLoadIds: MutableSet<Long>,
    ): AreaOutcome? {
        val summary = deficientSummaryOrNull(area, clientId) ?: return null
        if (transportOrderPort.hasOpenAreaReplenishment(area.itemDataAreaId, clientId)) return null

        val query = SourceQuery(
            itemDataId = area.itemDataId,
            clientId = clientId,
            targetLocationId = NO_SINGLE_TARGET_FACE,
            faceLotNumbers = emptySet(),
            targetIsPickingFace = false,
            fromPicking = false,
            fixFaceLocationIds = fixFaceLocationIds,
            excludeLocationIds = area.clusterLocationIds,
            // .toSet() snapshots the claim set as of THIS call -- see ReplenishmentService.
            // processFixAssignment's identical comment for why a live MutableSet reference
            // would be wrong here.
            excludeUnitLoadIds = claimedUnitLoadIds.toSet(),
        )
        val source = sourceSelector.selectSource(query)
            ?: return AreaOutcome.Shortfall(areaShortfall(area, summary.totalAmount, "NO_SOURCE"))
        // Row 2 (defect-burndown-4, Task 5): fix faces are excluded from destination candidacy
        // (see chooseDestination) -- an area whose cluster is fully fix-assigned reports
        // NO_DESTINATION rather than double-booking a face Mode-1 already owns.
        val destination = chooseDestination(area, clientId, fixFaceLocationIds)
            ?: return AreaOutcome.Shortfall(areaShortfall(area, summary.totalAmount, "NO_DESTINATION"))
        val (destLocationId, destLocationName) = destination
        // Row 3 (defect-burndown-4, Task 5): claim the source for the REST of this pass
        // (fix-face loop already ran; this area loop and any later area both see the claim).
        claimedUnitLoadIds += source.unitLoadId

        val ref = transportOrderPort.createAreaReplenishment(
            AreaReplenishmentTaskCommand(
                clientId = clientId,
                itemDataId = area.itemDataId,
                itemDataNumber = area.itemDataNumber,
                itemDataAreaId = area.itemDataAreaId,
                unitLoadId = source.unitLoadId,
                destinationLocationId = destLocationId,
                destinationLocationName = destLocationName,
            ),
        )
        return AreaOutcome.Generated(
            GeneratedTask(
                taskId = ref.id,
                orderNumber = ref.orderNumber,
                fixAssignmentId = null,
                locationName = destLocationName,
                itemDataNumber = area.itemDataNumber,
                unitLoadId = source.unitLoadId,
                itemDataAreaId = area.itemDataAreaId,
            ),
        )
    }

    /** Skip an area with neither threshold configured (avoids an unnecessary [stockSummaryLookup]
     * query) or with neither threshold missed. See
     * `docs/functional/replenishment.md#4-area-level-replenishment`: deficiency is EITHER
     * threshold missing its target, not both. */
    private fun deficientSummaryOrNull(area: ItemDataAreaView, clientId: Long): StockSummary? {
        val plannedAmount = area.plannedAmount
        val plannedStocks = area.plannedStocks
        val hasTarget = (plannedAmount != null && plannedAmount.signum() > 0) || (plannedStocks != null && plannedStocks > 0)
        if (!hasTarget) return null
        val summary = stockSummaryLookup.summaryInLocations(area.itemDataId, clientId, area.clusterLocationIds)
        val amountDeficient = plannedAmount != null && summary.totalAmount < plannedAmount
        val stocksDeficient = plannedStocks != null && summary.stockCount < plannedStocks
        return summary.takeIf { amountDeficient || stocksDeficient }
    }

    private fun areaShortfall(area: ItemDataAreaView, currentAmount: BigDecimal, reason: String) =
        ReplenishmentShortfall(
            fixAssignmentId = null,
            locationName = "AREA-${area.itemDataAreaId}",
            itemDataNumber = area.itemDataNumber,
            currentAmount = currentAmount,
            minAmount = area.plannedAmount,
            reason = reason,
            itemDataAreaId = area.itemDataAreaId,
        )

    /**
     * Row 2 (defect-burndown-4, Task 5): [fixFaceLocationIds] is excluded from candidacy
     * BEFORE the lock/occupancy queries even run -- a fix-assigned location inside this area's
     * cluster is Mode-1's face, never a valid Mode-2 destination, regardless of its own
     * lock/allocation state. Without this, a single-location cluster that is entirely
     * fix-assigned would let the SAME location receive both a Mode-1 (fix-face) and a Mode-2
     * (area) REPLENISH order in one scan pass.
     *
     * Row 4 (defect-burndown-4, Task 5): among the remaining candidates, both LOCKED
     * ([LocationLockPort.lockedLocationIds]) and OCCUPIED ([LocationLockPort.occupiedLocationIds],
     * effective allocation >= 100) locations are excluded before the lowest-id pick -- the
     * pre-Task-5 version picked lowest-id unconditionally, which could hand out a location that
     * was already full or soft-reserved by an in-flight putaway.
     */
    private fun chooseDestination(area: ItemDataAreaView, clientId: Long, fixFaceLocationIds: Set<Long>): Pair<Long, String>? {
        val candidateIds = area.clusterLocationIds.filterNot { it in fixFaceLocationIds }
        if (candidateIds.isEmpty()) return null
        val locked = locationLockPort.lockedLocationIds(candidateIds, clientId)
        val occupied = locationLockPort.occupiedLocationIds(candidateIds, clientId)
        val candidateId = candidateIds.filter { it !in locked && it !in occupied }.minOrNull() ?: return null
        val name = locationLockPort.locationName(candidateId, clientId) ?: return null
        return candidateId to name
    }
}

package com.karyo.replenishment.service

import com.karyo.auth.spi.RuntimePropertyLookup
import com.karyo.inventory.api.spi.ReplenishmentSource
import com.karyo.inventory.api.spi.ReplenishmentSourceSelector
import com.karyo.inventory.api.spi.SourceQuery
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.spi.FixAssignmentLookup
import com.karyo.layout.spi.FixAssignmentView
import com.karyo.layout.spi.LocationAreaUsageLookup
import com.karyo.replenishment.dto.GeneratedTask
import com.karyo.replenishment.dto.ReplenishmentNeed
import com.karyo.replenishment.dto.ReplenishmentScanResult
import com.karyo.replenishment.dto.ReplenishmentShortfall
import com.karyo.replenishment.spi.ReplenishmentStrategy
import com.karyo.tasks.spi.ReplenishmentTaskCommand
import com.karyo.tasks.spi.TransportOrderPort
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.math.BigDecimal

/** SC16 catalog key backing [SourceQuery.fromPicking] — see the auth module's `SystemPropertyCatalog` for the myWMS analogue + default rationale. */
private const val FROM_PICKING_KEY = "karyo.replenishment.from-picking"

/** One fix-face's scan outcome -- see `ReplenishmentService.processFixAssignment`. Same shape as
 *  `AreaOutcome` in [AreaReplenishmentService] for the Mode-2 (area-level) pass. */
private sealed class FixOutcome {
    data class Generated(val task: GeneratedTask) : FixOutcome()
    data class Shortfall(val shortfall: ReplenishmentShortfall) : FixOutcome()
}

@ApplicationScoped
class ReplenishmentService(
    private val fixAssignmentLookup: FixAssignmentLookup,
    private val sourceSelector: ReplenishmentSourceSelector,
    private val transportOrderPort: TransportOrderPort,
    private val strategyResolver: ReplenishmentStrategyResolver,
    private val stockUnitLookup: StockUnitLookup,
    private val locationAreaUsageLookup: LocationAreaUsageLookup,
    private val runtimeProperties: RuntimePropertyLookup,
    // Defect burndown 4 (Task 4): area-level (Mode 2) scan split out to keep this ctor under the
    // detekt 10-param ceiling -- see [AreaReplenishmentService]'s KDoc.
    private val areaReplenishmentService: AreaReplenishmentService,
) {
    private val log = Logger.getLogger(ReplenishmentService::class.java)

    fun scan(clientId: Long): ReplenishmentScanResult {
        val assignments = fixAssignmentLookup.listForReplenishment(clientId)
        val fixFaceLocationIds = assignments.map { it.locationId }.toSet()
        // R14 (Task 3): explicit-clientId reads, both scheduler-safe (see LocationAreaUsageLookup /
        // StockUnitLookup.lotNumbersAtLocation KDoc) — `scan` is reachable from ReplenishmentScheduler's
        // @Scheduled multi-tenant loop, which never primes an ambient TenantContext.
        val pickingLocationIds = locationAreaUsageLookup.pickingLocationIds(clientId)
        val strategy = strategyResolver.resolve()
        val generated = mutableListOf<GeneratedTask>()
        val shortfalls = mutableListOf<ReplenishmentShortfall>()
        // R15 (Task 4): resolved ONCE per scan (not per fix-assignment) — RuntimePropertyLookup
        // is explicit-clientId, scheduler-safe like the two lookups above, but a scan can iterate
        // hundreds of fix assignments and the toggle cannot change mid-scan, so one read suffices.
        val fromPicking = runtimeProperties.getBoolean(FROM_PICKING_KEY, clientId, false)
        // Row 3 (defect-burndown-4, Task 5): one claimed-source set for the WHOLE pass, shared by
        // the fix-face loop below AND the area pass -- seeded from every OPEN REPLENISH order's
        // source unit-load (either mode, any earlier pass), then grown with each newly-minted
        // order's source as this pass proceeds, so two deficiencies discovered in the SAME pass
        // can never claim the same unit-load either.
        val claimedUnitLoadIds = transportOrderPort.openReplenishmentUnitLoadIds(clientId).toMutableSet()

        for (a in assignments) {
            when (
                val outcome = processFixAssignment(
                    a, clientId, strategy, pickingLocationIds, fixFaceLocationIds, fromPicking, claimedUnitLoadIds,
                )
            ) {
                is FixOutcome.Generated -> generated += outcome.task
                is FixOutcome.Shortfall -> shortfalls += outcome.shortfall
                null -> Unit // null currentAmount, above-min, or an already-open task -- nothing to record
            }
        }
        // R12b (Task 6): area-level (Mode 2) pass, AFTER the fix-face loop above — the two
        // modes are independent (disjoint provenance columns, disjoint dedupe keys) so ordering
        // between them doesn't matter for correctness; fix-face first simply matches this
        // method's pre-existing shape (Mode 1 was built first, replenishment sprint Task 2/3).
        val (areaGenerated, areaShortfalls) = scanAreas(clientId, fixFaceLocationIds, claimedUnitLoadIds)
        generated += areaGenerated
        shortfalls += areaShortfalls
        return ReplenishmentScanResult(generated, shortfalls)
    }

    /**
     * One fix-face's worth of [scan]'s main loop -- pulled out of the loop body for the same
     * reason as `AreaReplenishmentService.processArea`: every early exit here is a `return` from
     * THIS function, not a `continue` in the caller's loop, so Detekt's `LoopWithTooManyJumpStatements` stays
     * satisfied by construction even with four distinct skip conditions (null `currentAmount`,
     * above-min, already-open task, no source).
     *
     * Row 1 (defect-burndown-4, Task 1): a `null` [FixAssignmentView.currentAmount] (the stock
     * read failed) is logged at WARN and skipped BEFORE [strategy] ever sees it -- never coerced
     * to zero, which would fabricate a false `needsReplenishment`.
     */
    private fun processFixAssignment(
        a: FixAssignmentView,
        clientId: Long,
        strategy: ReplenishmentStrategy,
        pickingLocationIds: Set<Long>,
        fixFaceLocationIds: Set<Long>,
        fromPicking: Boolean,
        claimedUnitLoadIds: MutableSet<Long>,
    ): FixOutcome? {
        val currentAmount = a.currentAmount ?: run {
            log.warn(
                "Skipping fix assignment ${a.assignmentId} (location ${a.locationName}): " +
                    "currentAmount read failed, not treating as zero",
            )
            return null
        }
        if (!strategy.needsReplenishment(currentAmount, a.minAmount, a.maxAmount, a.desiredAmount)) return null
        if (transportOrderPort.hasOpenReplenishment(a.assignmentId, clientId)) return null

        val query = SourceQuery(
            itemDataId = a.itemDataId,
            clientId = clientId,
            targetLocationId = a.locationId,
            faceLotNumbers = stockUnitLookup.lotNumbersAtLocation(a.locationId, clientId),
            targetIsPickingFace = a.locationId in pickingLocationIds,
            fromPicking = fromPicking,
            fixFaceLocationIds = fixFaceLocationIds,
            // .toSet() snapshots the claim set as of THIS call -- claimedUnitLoadIds keeps
            // growing for later assignments/areas in the pass, and a query built from a live
            // MutableSet reference would silently reflect those LATER mutations too.
            excludeUnitLoadIds = claimedUnitLoadIds.toSet(),
        )
        val source = sourceSelector.selectSource(query)
            ?: return FixOutcome.Shortfall(
                ReplenishmentShortfall(
                    fixAssignmentId = a.assignmentId,
                    locationName = a.locationName,
                    itemDataNumber = a.itemDataNumber,
                    currentAmount = currentAmount,
                    minAmount = a.minAmount,
                    reason = "NO_SOURCE",
                ),
            )
        // Row 3 (defect-burndown-4, Task 5): claim the source for the REST of this pass (later
        // fix assignments AND the area pass that follows).
        claimedUnitLoadIds += source.unitLoadId

        val ref = transportOrderPort.createReplenishment(
            ReplenishmentTaskCommand(
                clientId = clientId,
                unitLoadId = source.unitLoadId,
                destinationLocationId = a.locationId,
                destinationLocationName = a.locationName,
                fixAssignmentId = a.assignmentId,
                amount = topUpAmount(a, currentAmount, source),
            ),
        )
        return FixOutcome.Generated(
            GeneratedTask(
                taskId = ref.id,
                orderNumber = ref.orderNumber,
                fixAssignmentId = a.assignmentId,
                locationName = a.locationName,
                itemDataNumber = a.itemDataNumber,
                unitLoadId = source.unitLoadId,
            ),
        )
    }

    /**
     * R12b (Task 6) -- area-level (Mode 2) replenishment scan. Split out to
     * [AreaReplenishmentService] (defect burndown 4, Task 4, row 10) so the correctness fixes
     * Task 5 adds do not push this class's constructor past the 10-param detekt ceiling -- see
     * that class's KDoc for the full algorithm (deficiency rule, dedupe guard, destination
     * choice) and for the row-8 Mode-2 tiering ruling. This is a one-line delegate so callers of
     * [scan] (the only caller of this method) see no behavior change.
     */
    private fun scanAreas(
        clientId: Long,
        fixFaceLocationIds: Set<Long>,
        claimedUnitLoadIds: MutableSet<Long>,
    ): Pair<List<GeneratedTask>, List<ReplenishmentShortfall>> =
        areaReplenishmentService.scanAreas(clientId, fixFaceLocationIds, claimedUnitLoadIds)

    /**
     * R13 — NEW BEHAVIOR (Karyo-original fill-to-max): legacy myWMS replenishment never
     * computed a quantity and never read `FixAssignment.maxAmount` — it always moved the whole
     * reserve unit-load. This computes a top-up deficit ONLY when [FixAssignmentView.maxAmount]
     * is configured: `deficit = maxAmount - currentAmount`. A non-positive deficit (face already
     * at/above max — shouldn't normally coincide with `needsReplenishment` returning true, but
     * defensive regardless) or a null `maxAmount` both fall back to `null` (whole-UL move, the
     * parity behavior). Otherwise the requested quantity is capped at the source's
     * [ReplenishmentSource.availableAmount] (row 5, defect-burndown-4 Task 1 -- NOT the gross
     * [ReplenishmentSource.amount]; a source partially reserved elsewhere must never be asked to
     * give up more than what is actually free to move). [ReplenishmentTaskCommand.amount] is only
     * set to a genuine partial (`requested < source.amount`, the GROSS figure -- a fully-available
     * source whose deficit still reaches its whole gross amount stays a whole-UL move); when the
     * capped request would consume the entire source, `null` is passed instead so the whole-UL
     * path is used (equivalent in outcome but avoids a spurious "partial" that would leave nothing
     * behind).
     */
    private fun topUpAmount(a: FixAssignmentView, currentAmount: BigDecimal, source: ReplenishmentSource): BigDecimal? {
        val maxAmount = a.maxAmount ?: return null
        val deficit = maxAmount.subtract(currentAmount)
        if (deficit.signum() <= 0) return null
        val requested = if (source.availableAmount < deficit) source.availableAmount else deficit
        return if (requested < source.amount) requested else null
    }

    /**
     * Row 1 (defect-burndown-4, Task 1): a face whose [FixAssignmentView.currentAmount] is `null`
     * (the stock read itself failed -- see that field's KDoc) is excluded from the detector view
     * entirely, the same "unknown, not zero" treatment [scan] gives it -- a null read must never
     * be able to report a false `belowMin`.
     */
    fun needs(clientId: Long): List<ReplenishmentNeed> {
        val strategy = strategyResolver.resolve()
        return fixAssignmentLookup.listForReplenishment(clientId).mapNotNull { a ->
            val currentAmount = a.currentAmount ?: return@mapNotNull null
            ReplenishmentNeed(
                fixAssignmentId = a.assignmentId,
                locationId = a.locationId,
                locationName = a.locationName,
                itemDataId = a.itemDataId,
                itemDataNumber = a.itemDataNumber,
                currentAmount = currentAmount,
                minAmount = a.minAmount,
                desiredAmount = a.desiredAmount,
                belowMin = strategy.needsReplenishment(currentAmount, a.minAmount, a.maxAmount, a.desiredAmount),
                hasOpenTask = transportOrderPort.hasOpenReplenishment(a.assignmentId, clientId),
            )
        }
    }
}

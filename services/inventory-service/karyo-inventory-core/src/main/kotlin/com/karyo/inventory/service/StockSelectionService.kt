package com.karyo.inventory.service

import com.karyo.inventory.api.dto.StockSelectionResponse
import com.karyo.inventory.api.spi.StockSelectionFilter
import com.karyo.inventory.api.vo.PickStockResult
import com.karyo.inventory.api.vo.PickingType
import com.karyo.inventory.api.vo.StockSelectionRequest
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.repository.InactiveProductRepository
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.layout.spi.FixAssignmentLookup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.math.BigDecimal
import com.karyo.inventory.api.vo.CompleteHandling

/**
 * Implements the 13-pass stock selection algorithm -- behavioral parity with myWMS PickingStockFinder, independently implemented from the workflow analyses.
 *
 * Pass structure (from myWMS analysis):
 *   Passes 1-5:  preferComplete candidates (lot-filtered or not, various combos)
 *   Passes 6-9:  partial pick candidates
 *   Passes 10-11: locked stock (if useLockedStock = true)
 *   Passes 12-13: fallback passes
 *
 * FIFO ordering within each pass: strategyDate ASC, amount ASC, created ASC, id ASC
 */
@ApplicationScoped
class StockSelectionService(
    private val stockUnitRepository: StockUnitRepository,
    private val inactiveProductRepository: InactiveProductRepository,
    private val filters: Instance<StockSelectionFilter>,
    private val optimizer: Optimizer,
    private val fixAssignmentLookup: FixAssignmentLookup,
) {

    // Irreducibly branchy: the myWMS-fidelity selection (preferMatching + completeHandling +
    // the 13-pass FIFO accumulation). Covered by StockSelectionServiceTest; kept as one method
    // so the pass ordering reads top-to-bottom.
    @Suppress("CyclomaticComplexMethod")
    fun selectStock(request: StockSelectionRequest): StockSelectionResponse {
        // Guard: return empty result immediately if product is inactive
        if (inactiveProductRepository.isInactive(request.itemDataId, request.clientId)) {
            return StockSelectionResponse(
                stocks = emptyList(),
                totalAvailable = BigDecimal.ZERO,
                fullyFulfilled = false,
            )
        }

        var remaining = request.amount
        val results = mutableListOf<PickStockResult>()
        val selectedIds = mutableSetOf<Long>()

        // Resolve completeMode early: completeHandling is the stronger constraint and must win
        // over preferMatching (a mere preference). Both blocks read the same resolved value.
        val completeMode = CompleteHandling.fromCode(request.completeHandling)

        // preferMatching: an exact-amount unit wins over FIFO preference (myWMS passes 1/7/8).
        // Skipped when completeHandling is active — the complete-only contract takes precedence.
        if (request.preferMatching && !completeMode.isCompleteOnly) {
            val exact = exactMatchCandidate(request, remaining)
            if (exact != null) {
                results.add(toPick(exact, remaining))
                selectedIds.add(exact.id!!)
                remaining = BigDecimal.ZERO
            }
        }

        // completeHandling: when enabled, satisfy demand from COMPLETE unit loads only (or nothing).
        if (completeMode.isCompleteOnly && remaining > BigDecimal.ZERO) {
            val complete = selectCompleteHandling(request, completeMode, remaining)
            complete.forEach { pick ->
                results.add(pick)
                selectedIds.add(pick.stockUnitId)
                remaining = remaining.subtract(pick.suggestedPickAmount ?: BigDecimal.ZERO)
            }
            // complete-or-nothing: never fall through to partial/FIFO passes.
            val total = results.sumOf { it.availableAmount }
            return StockSelectionResponse(results, total, remaining <= BigDecimal.ZERO)
        }

        for (pass in 1..13) {
            if (remaining <= BigDecimal.ZERO) break

            val candidates = getCandidatesForPass(pass, request)
            val filtered = applyFilters(candidates, request)
            val pickCeilings = fixPickCeilingsFor(filtered, request)

            for (su in filtered) {
                if (remaining <= BigDecimal.ZERO) break

                // Skip stock units already selected in earlier passes
                if (su.id!! in selectedIds) continue

                val available = su.availableAmount
                // L7: myWMS FixAssignment.maxPickAmount — a soft per-pick ceiling on a fixed
                // slot. When set for THIS product at this location and less than what's still
                // needed for this request, myWMS skips the fixed slot entirely (forces a split
                // across other stock, or a shortfall per existing semantics below) rather than
                // partially draining it below the caller's ask — partial-take against a fix
                // slot would change pick-generation semantics beyond this row's scope. The
                // slot is NOT permanently excluded: a later pass may re-offer it once other
                // picks have shrunk `remaining` to or below the ceiling. Merged into one
                // `continue` with the plain availability check (detekt's LoopWithTooManyJump-
                // Statements budget for this loop is already spent by the other jumps here).
                if (available <= BigDecimal.ZERO || exceedsFixCeiling(su, pickCeilings, remaining)) continue

                val pickAmount = available.min(remaining)

                results.add(toPick(su, pickAmount))
                selectedIds.add(su.id!!)
                remaining = remaining.subtract(pickAmount)
            }
        }

        val totalAvailable = results.sumOf { it.availableAmount }
        return StockSelectionResponse(
            stocks = results,
            totalAvailable = totalAvailable,
            fullyFulfilled = remaining <= BigDecimal.ZERO,
        )
    }

    /**
     * Returns candidates for the given pass number.
     *
     * Pass mapping:
     *  1: preferComplete + lot filter
     *  2: preferComplete + no lot filter
     *  3: preferComplete + lot filter (relaxed)
     *  4: preferComplete + no lot filter (relaxed)
     *  5: preferComplete fallback
     *  6: partial + lot filter
     *  7: partial + no lot filter
     *  8: partial + lot filter (relaxed)
     *  9: partial + no lot filter
     *  10: locked + lot filter
     *  11: locked + no lot filter
     *  12: final fallback + lot filter
     *  13: final fallback + no lot filter
     */
    private fun getCandidatesForPass(pass: Int, request: StockSelectionRequest): List<StockUnit> {
        val includeLocked = pass in 10..11
        val lotNumber = when (pass) {
            1, 3, 6, 8, 10, 12 -> request.lotNumber
            else -> null
        }

        // Strict lot: when a lot is requested and enforced, never run the any-lot passes.
        if (request.enforceLot && request.lotNumber != null && lotNumber == null) return emptyList()

        // Passes 1-5 require preferComplete flag
        if (pass in 1..5 && !request.preferComplete) return emptyList()

        // Passes 10-11 require useLockedStock flag
        if (pass in 10..11 && !request.useLockedStock) return emptyList()

        val candidates = stockUnitRepository.findForSelection(
            itemDataId = request.itemDataId,
            clientId = request.clientId,
            includeLocked = includeLocked,
            lotNumber = lotNumber,
        )

        // Excluded stock units (e.g. a just-short source on a follow-up re-pick) never appear as
        // candidates in any pass; default-empty so existing callers are unaffected.
        val included = if (request.excludeStockUnitIds.isEmpty()) candidates
        else candidates.filterNot { it.id in request.excludeStockUnitIds }

        // Passes 1-5: only return candidates where available amount covers the full request
        return if (pass in 1..5) {
            included.filter { it.availableAmount >= request.amount }
        } else {
            included
        }
    }

    /**
     * Applies the SPI filter chain (sorted by priority ascending) to the candidate stock
     * unit IDs, then returns the survivors **in the order the chain produced** — filters may
     * prune *and re-rank* candidates ([StockSelectionFilter] honors reorder, matching
     * layout's `LocationFilter`; ADR-036 Strategy-SPI / filter convention). IDs the chain
     * returns that are not in the candidate set are dropped.
     */
    private fun applyFilters(candidates: List<StockUnit>, request: StockSelectionRequest): List<StockUnit> {
        if (candidates.isEmpty()) return candidates

        val sortedFilters = filters.stream()
            .sorted(compareBy { it.priority() })
            .toList()

        var ids = candidates.map { it.id!! }
        for (filter in sortedFilters) {
            ids = filter.filter(ids, request)
        }

        val byId = candidates.associateBy { it.id!! }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * L7: batched read of [FixAssignmentLookup.pickCeilings] for one candidate set — one
     * query per call, scoped to the locations actually offered and to the requested product.
     * Shared by all three selection entry points: the main 13-pass loop (per-pass candidates),
     * [exactMatchCandidate] (`preferMatching`), and [selectCompleteHandling] (`completeHandling`)
     * — the digest's ceiling rule carries no selection-mode qualifier, so all three must honor
     * it identically (fix-round 1: `preferMatching`/`completeHandling` were found bypassing it).
     */
    private fun fixPickCeilingsFor(candidates: List<StockUnit>, request: StockSelectionRequest): Map<Long, BigDecimal> {
        if (candidates.isEmpty()) return emptyMap()
        val locationIds = candidates.map { it.unitLoad.storageLocationId }.toSet()
        return fixAssignmentLookup.pickCeilings(request.clientId, request.itemDataId, locationIds)
    }

    /**
     * L7: true when [su]'s location carries a fix-assignment ceiling (from [ceilings], as
     * produced by [fixPickCeilingsFor]) that is less than [amount] — the still-unsatisfied
     * requested amount at the point this candidate is being considered (never the per-unit
     * take, which may legitimately be smaller — see [selectStock]'s main-loop comment and the
     * task-9 review's mutation-verified rationale for comparing against the request, not the
     * pick). A location with no ceiling row for this product never triggers this.
     */
    private fun exceedsFixCeiling(su: StockUnit, ceilings: Map<Long, BigDecimal>, amount: BigDecimal): Boolean {
        val ceiling = ceilings[su.unitLoad.storageLocationId] ?: return false
        return ceiling < amount
    }

    /**
     * Determines picking type - behavioral parity with myWMS's picking-type rules:
     * COMPLETE if: exact amount match, UL not opened, single stock on UL, ULType supports COMPLETE usage.
     * Otherwise: PICK (partial pick).
     */
    private fun determinePickingType(su: StockUnit, pickAmount: BigDecimal): PickingType {
        val isExactAmount = pickAmount.compareTo(su.amount) == 0
        val isNotOpened = !su.unitLoad.opened
        val isSingleStock = su.unitLoad.stockUnits.size == 1
        val hasCompleteUsage = su.unitLoad.unitLoadType.hasUsage("COMPLETE")

        return if (isExactAmount && isNotOpened && isSingleStock && hasCompleteUsage) {
            PickingType.COMPLETE
        } else {
            PickingType.PICK
        }
    }

    /**
     * Build a [PickStockResult] taking [amount] from [su].
     *
     * For COMPLETE picks (whole pallet), [amount] is set to the full [StockUnit.availableAmount],
     * which may EXCEED the caller's remaining demand — the oversized pallet is taken as-is.
     * Callers in the reservation layer clamp the actually-reserved quantity to
     * `min(suggestedPickAmount, remaining)` so the pallet transfer is correct without
     * over-reserving against other orders. This is intentional, matching myWMS complete-handling.
     */
    private fun toPick(su: StockUnit, amount: BigDecimal): PickStockResult {
        val take = su.availableAmount.min(amount)
        return PickStockResult(
            stockUnitId = su.id!!,
            unitLoadId = su.unitLoad.id!!,
            unitLoadLabel = su.unitLoad.labelId,
            locationId = su.unitLoad.storageLocationId,
            locationName = su.unitLoad.storageLocationName,
            availableAmount = su.availableAmount,
            suggestedPickAmount = take,
            pickingType = determinePickingType(su, take),
        )
    }

    /**
     * First eligible candidate (lot/lock/enforceLot respected) whose available amount == [target].
     *
     * Intentionally excludes locked stock ([includeLocked] = false): preferMatching is a
     * preference, never an override of the lock safety fence (consistent with myWMS prefer-matching
     * passes 1/7/8 which never include locked stock).
     *
     * L7 (fix round 1): an exact-amount candidate whose location has a `maxPickAmount` ceiling
     * below [target] is skipped here too — [selectStock] would otherwise take the *entire*
     * [target] from this one unit in a single line (`remaining = BigDecimal.ZERO`), which is
     * precisely the un-ceilinged drain the main loop's check exists to prevent. Skipping simply
     * makes this method return null for that candidate; the caller then falls through to its
     * existing next-best behavior (completeHandling, then the ceiling-aware 13-pass loop).
     */
    private fun exactMatchCandidate(request: StockSelectionRequest, target: BigDecimal): StockUnit? {
        val lot = request.lotNumber
        val candidates = stockUnitRepository.findForSelection(
            itemDataId = request.itemDataId,
            clientId = request.clientId,
            includeLocked = false,
            lotNumber = lot,
        )
        val scoped = applyFilters(candidates, request)
        val ceilings = fixPickCeilingsFor(scoped, request)
        return scoped.firstOrNull {
            it.availableAmount.compareTo(target) == 0 && !exceedsFixCeiling(it, ceilings, target)
        }
    }

    /**
     * Strict complete-handling validity (myWMS `isValidForSeparatedCompleteHandling`, minus the
     * STORAGE-area / not-on-fixed-location checks — selection is location-blind until Spec B):
     * unopened UL, no existing reservation, single stock on the UL, UL type allows COMPLETE.
     */
    private fun isStrictComplete(su: StockUnit): Boolean =
        !su.unitLoad.opened &&
            su.reservedAmount.compareTo(BigDecimal.ZERO) == 0 &&
            su.unitLoad.stockUnits.size == 1 &&
            su.unitLoad.unitLoadType.hasUsage("COMPLETE")

    /**
     * L7 (fix round 1): strict-complete candidates whose location carries a `maxPickAmount`
     * ceiling below [target] are excluded before any mode picks from them — every mode below
     * (`AMOUNT_FIRST_MATCH`'s exact take, `AMOUNT_FIRST_PLUS`'s whole-oversized-pallet take,
     * and the combination modes' full-pallet contributions) would otherwise take an amount
     * from that unit with zero reference to the ceiling, same defect class as
     * [exactMatchCandidate]'s pre-fix-round-1 gap. Filtering the candidate pool up front means
     * each mode's existing "no candidate found" fallback (`emptyList()` / optimizer returning
     * null) is the natural fall-through — no per-mode special-casing needed.
     */
    private fun selectCompleteHandling(
        request: StockSelectionRequest,
        mode: CompleteHandling,
        target: BigDecimal,
    ): List<PickStockResult> {
        val strictCandidates = applyFilters(
            stockUnitRepository.findForSelection(
                itemDataId = request.itemDataId,
                clientId = request.clientId,
                includeLocked = request.useLockedStock,
                lotNumber = request.lotNumber,
            ),
            request,
        ).filter { isStrictComplete(it) }

        val ceilings = fixPickCeilingsFor(strictCandidates, request)
        val candidates = strictCandidates.filterNot { exceedsFixCeiling(it, ceilings, target) }

        return when (mode) {
            CompleteHandling.AMOUNT_FIRST_MATCH ->
                candidates.firstOrNull { it.availableAmount.compareTo(target) == 0 }
                    ?.let { listOf(toPick(it, target)) } ?: emptyList()
            CompleteHandling.AMOUNT_FIRST_PLUS ->
                candidates.firstOrNull { it.availableAmount >= target }
                    ?.let { listOf(toPick(it, it.availableAmount)) } ?: emptyList()
            CompleteHandling.AMOUNT_MATCH,
            CompleteHandling.AMOUNT_SMALLEST_DIFF,
            CompleteHandling.AMOUNT_SMALLEST_PLUS -> {
                val byAmount = candidates.groupBy { it.availableAmount }.toMutableMap()
                val chosenAmounts = optimizer.findBestCombination(
                    candidates.map { it.availableAmount }, target, mode,
                ) ?: return emptyList()
                // Map each chosen amount back to a distinct candidate (FIFO order preserved).
                chosenAmounts.mapNotNull { amt ->
                    val bucket = byAmount[amt]
                    val su = bucket?.firstOrNull()
                    if (su != null) {
                        byAmount[amt] = bucket.drop(1)
                        toPick(su, su.availableAmount)
                    } else null
                }
            }
            else -> emptyList()
        }
    }
}

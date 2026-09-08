package com.karyo.inventory.service

import com.karyo.inventory.api.spi.ReplenishmentSource
import com.karyo.inventory.api.spi.ReplenishmentSourceSelector
import com.karyo.inventory.api.spi.SourceQuery
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.layout.spi.LocationAreaUsageLookup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Default [ReplenishmentSourceSelector] — R14, lot-aware + fixed-reserve-first tiered search;
 * R15 (Task 4) adds the destination-face strictness split ([meetsStrictness]). See
 * [ReplenishmentSourceSelector]'s KDoc for the full algorithm and the Karyo decisions it makes
 * beyond the legacy corpus (per-candidate strictness, lot preference rather than hard filter,
 * lot outranking tier).
 *
 * Delegates the base candidate set to [StockUnitRepository.findForSelection], which already
 * applies:
 *   - state = ON_STOCK (300)
 *   - amount > reservedAmount  (the picking-side looseness; strictness is tightened in-memory
 *     per [SourceQuery.targetIsPickingFace], see [meetsStrictness])
 *   - lockType = 0             (unlocked only, both stock and unit-load)
 *   - order: strategyDate asc, amount asc, created asc, id asc  (FIFO)
 *
 * Read-only throughout — no reservation or state side-effect.
 */
@ApplicationScoped
class DefaultReplenishmentSourceSelector(
    private val stockUnitRepository: StockUnitRepository,
    private val locationAreaUsageLookup: LocationAreaUsageLookup,
) : ReplenishmentSourceSelector {

    @Transactional(Transactional.TxType.SUPPORTS)
    override fun selectSource(q: SourceQuery): ReplenishmentSource? {
        val pickingLocationIds = locationAreaUsageLookup.pickingLocationIds(q.clientId)
        val eligible = stockUnitRepository.findForSelection(q.itemDataId, q.clientId)
            .filter { it.unitLoad.storageLocationId != q.targetLocationId }
            .filter { it.unitLoad.storageLocationId !in q.excludeLocationIds }
            // Row 3 (defect-burndown-4, Task 5): a unit-load already claimed by another
            // deficiency this pass (or by a still-open REPLENISH order) is never re-selected.
            .filter { it.unitLoad.id !in q.excludeUnitLoadIds }
            .filter { q.fromPicking || it.unitLoad.storageLocationId !in pickingLocationIds }
            .filter { meetsStrictness(it, pickingLocationIds, q.targetIsPickingFace) }

        return firstOfTier(preferredPool(eligible, q.faceLotNumbers), q.fixFaceLocationIds, pickingLocationIds)
            ?.let { ReplenishmentSource(unitLoadId = it.unitLoad.id!!, amount = it.amount, availableAmount = it.availableAmount) }
    }

    /**
     * R15 (Task 4) - [SourceQuery.targetIsPickingFace] is now consumed here (Task 3 left it
     * wired-but-unread, see that field's KDoc). Public behavioral contract:
     * `docs/functional/replenishment.md#2-source-selection`.
     *
     * - `targetIsPickingFace == true`: a candidate on a PICKING-usage location, reachable only
     *   when [SourceQuery.fromPicking] is true, uses the base query's own
     *   `amount > reservedAmount` looseness; every other candidate is strict.
     * - `targetIsPickingFace == false`: every candidate must have `reservedAmount = 0` and a
     *   non-mixed unit load. A storage-face scan never takes the loose branch, regardless of the
     *   candidate's own location.
     */
    private fun meetsStrictness(candidate: StockUnit, pickingLocationIds: Set<Long>, targetIsPickingFace: Boolean): Boolean {
        val looseEligible = targetIsPickingFace && candidate.unitLoad.storageLocationId in pickingLocationIds
        return looseEligible || (candidate.reservedAmount.signum() == 0 && isNonMixedUnitLoad(candidate.unitLoad.id!!))
    }

    /**
     * "Non-mixed" mirrors [com.karyo.tasks.service.ConfirmVariantService.singleLiveStockOrThrow]'s
     * definition — exactly one live (non-DELETABLE) stock row on the unit-load — rather than
     * re-deriving the corpus's own itemData-mismatch check independently, so the codebase has one
     * "is this unit-load safe to move as a single thing" definition, not two.
     */
    private fun isNonMixedUnitLoad(unitLoadId: Long): Boolean =
        stockUnitRepository.findByUnitLoadId(unitLoadId).count { it.state != StockState.DELETABLE.code } <= 1

    /** Lot preference (Karyo: prefer-not-require) — falls back to the full pool when no lot matches. */
    private fun preferredPool(eligible: List<StockUnit>, faceLotNumbers: Set<String>): List<StockUnit> {
        if (faceLotNumbers.isEmpty()) return eligible
        val matching = eligible.filter { it.lotNumber != null && it.lotNumber in faceLotNumbers }
        return matching.ifEmpty { eligible }
    }

    /**
     * FIFO-first of Phase A (a fix-assigned location that is ALSO not itself a picking-usage
     * location) if any exist, else FIFO-first of Phase B. [pool] is already FIFO-ordered (both
     * filters above preserve order), so `firstOrNull()` on the partition IS "FIFO-first of that
     * phase".
     *
     * Task 3 review IMPORTANT-3: [pickingLocationIds] is excluded from Phase A membership on
     * purpose. `fixFaceLocationIds` is every fix-assigned location for the tenant, which includes
     * OTHER pick faces (a location can be both `fromPicking`-eligible AND fix-assigned — a normal
     * pick face IS a fix assignment). Without this exclusion, once [SourceQuery.fromPicking] is
     * true (R15/Task 4), a candidate sitting on another fix-assigned PICK FACE would rank
     * top-priority in Phase A and this selector would rob one pick face to feed another. Phase A
     * is reserved for fixed reserve or bulk storage locations, never fixed PICKING locations. A
     * fix-assigned reserve or bulk slot is disjoint from a fix-assigned pick face by construction
     * once this exclusion is applied. R15 (Task 4) is what makes this
     * exclusion observable: [pool] can now contain a picking-location candidate once a scan's
     * resolved [SourceQuery.fromPicking] is true, so without this exclusion such a candidate
     * would wrongly win Phase A whenever it also happens to be fix-assigned (see Test 10 in
     * `ReplenishmentSourceSelectorTest`).
     *
     * **Row 8 (defect burndown 4, Task 4) -- Mode-2 tiering ruling.** Area (Mode-2) targets
     * deliberately reuse this same fix-face Phase-A tiering (fix-assigned reserve slots first).
     * The myWMS corpus ordered Mode-2 sources by the StorageStrategy cluster set instead; Karyo
     * unifies on reserve-first as the designated-refill-source reading. Decided 2026-08-15
     * (defect burndown 4).
     */
    private fun firstOfTier(pool: List<StockUnit>, fixFaceLocationIds: Set<Long>, pickingLocationIds: Set<Long>): StockUnit? =
        pool.firstOrNull { it.unitLoad.storageLocationId in fixFaceLocationIds && it.unitLoad.storageLocationId !in pickingLocationIds }
            ?: pool.firstOrNull()
}

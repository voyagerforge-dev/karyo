package com.karyo.inventory.service

import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.product.spi.ProductLookup
import com.karyo.product.spi.ProductMeasures
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Row 16: recomputes a unit load's weight from its type's tare plus the weight of everything
 * still on it, as specified in `docs/functional/inventory-operations.md#3-unit-load-weight`.
 * Closes a live bug:
 * nothing wrote [UnitLoad.weight] before this class existed, so
 * `StockUnitRepository.findOnStockUnitLoadWeightByLocationIds` (and every reader of it, such as
 * a location group's lifting-capacity check) always summed zero.
 *
 * Recomputation is always FULL, never incremental: myWMS maintained this incrementally with a
 * full-recalculation fallback whenever the increment produced a nonsense value, a
 * cache-coherence design with a repair hatch. A full recompute at each of the few mutation
 * points costs one batched product-measures read and cannot drift.
 *
 * Writes all three fields: [UnitLoad.weightCalculated] gets the computation, and [UnitLoad.weight]
 * gets the effective value ([UnitLoad.weightMeasure] when an operator has set one, otherwise the
 * computation). An item with no weight on file contributes nothing rather than nulling the total:
 * an honest partial number is more useful to the gross-weight-by-location query than a null it
 * would read as "no data", and the alternative would hide real weight behind one unmeasured SKU.
 *
 * "Still on it" reuses [UnitLoadTerminator.GONE_STATES] -- the same gone-predicate that decides
 * whether a stock unit still keeps a unit load alive -- rather than re-deriving a different
 * threshold under a second name.
 */
@ApplicationScoped
class UnitLoadWeightCalculator(
    private val stockUnitRepository: StockUnitRepository,
    private val productLookup: ProductLookup,
) {
    fun recalculate(unitLoad: UnitLoad) {
        val stock = stockUnitRepository.findByUnitLoadId(unitLoad.id!!)
            .filter { it.state !in UnitLoadTerminator.GONE_STATES }
        val measures = productLookup.findMeasuresByIds(stock.map { it.itemDataId }.toSet())
        applyWeights(unitLoad, stock, measures)
    }

    /**
     * Row :1411 (defect-burndown-5): batch sibling of [recalculate] for callers that need to
     * recompute MANY unit loads in one shot -- currently [UnitLoadTypeService.update] (every
     * unit load of a type whose tare just changed) and [com.karyo.demo.gen.InventoryGenerator]
     * (every unit load the demo just placed). NEVER call [recalculate] in a loop for this: that
     * is exactly the N+1 this overload exists to avoid.
     *
     * Bounded at (2 + distinct client count) queries total, regardless of how many unit loads
     * are passed: ONE plural stock read ([StockUnitRepository.findByUnitLoadIds]), then ONE
     * [ProductLookup.findMeasuresByIds] call PER DISTINCT CLIENT represented in that stock
     * (never per unit load, never per stock unit), then an in-memory per-unit-load compute and a
     * dirty-check flush at the caller's transaction boundary (this method does not flush itself
     * -- callers already run inside a `@Transactional` method, same as every other mutation
     * point [recalculate] is called from). A no-op ([unitLoads] empty) short-circuits before any
     * query.
     *
     * **Per-client product-measures split (CRITICAL fix, defect-burndown-5 task-3 review):**
     * [UnitLoadType] is a shared catalog row (`BaseEntity`, no tenant of its own), so
     * [UnitLoadRepository.findByUnitLoadTypeId] -- the query that feeds
     * [UnitLoadTypeService.update]'s call into this method -- is deliberately unscoped and can
     * return unit loads belonging to MULTIPLE clients in one call. The single-client
     * [ProductLookup.findMeasuresByIds] overload is ambient-`TenantContext`-scoped: called once
     * over the union of every client's item ids, it would silently drop every OTHER client's
     * items under the calling principal's own scope, and [applyWeights] treats a missing measure
     * as an honest "unmeasured item, contributes nothing" -- turning that silent drop into a
     * cross-tenant bug where client A's PUT on a shared type could persist a tare-only total onto
     * client B's unit loads. Grouping stock by [StockUnit.clientId] and calling the
     * explicit-`clientId` overload once per group (same pattern as
     * [com.karyo.product.spi.ProductLookup.findNumbersByIds]'s explicit-`clientId` overload, and
     * the same class of ambient-read-in-a-multi-tenant-loop bug the replenishment sprint's Task 3
     * lesson names) fixes this while staying batched: the extra query count is bounded by the
     * number of DISTINCT clients actually represented, not by unit-load or stock-unit count.
     * [recalculate] (the single-UL sibling) is unaffected -- a caller passing one unit load only
     * ever touches that load's own client, so the ambient scope there was never the bug.
     */
    fun recalculateAll(unitLoads: List<UnitLoad>) {
        if (unitLoads.isEmpty()) return
        val unitLoadIds = unitLoads.mapNotNull { it.id }
        val stockByUnitLoadId = stockUnitRepository.findByUnitLoadIds(unitLoadIds)
            .filter { it.state !in UnitLoadTerminator.GONE_STATES }
            .groupBy { it.unitLoad.id }
        val measures = stockByUnitLoadId.values.asSequence().flatten()
            .groupBy { it.clientId }
            .flatMap { (clientId, stockForClient) ->
                val itemDataIds = stockForClient.map { it.itemDataId }.toSet()
                productLookup.findMeasuresByIds(itemDataIds, clientId).entries
            }
            .associate { it.key to it.value }
        unitLoads.forEach { unitLoad ->
            applyWeights(unitLoad, stockByUnitLoadId[unitLoad.id].orEmpty(), measures)
        }
    }

    /** Shared compute step behind [recalculate] and [recalculateAll] -- see [recalculate]'s
     * class KDoc for the formula (tare plus non-gone content) and the effective-weight rule. */
    private fun applyWeights(unitLoad: UnitLoad, stock: List<StockUnit>, measures: Map<Long, ProductMeasures>) {
        val content = stock.fold(BigDecimal.ZERO) { acc, su ->
            val each = measures[su.itemDataId]?.weight ?: return@fold acc
            acc.add(each.multiply(su.amount))
        }
        val tare = unitLoad.unitLoadType.weight ?: BigDecimal.ZERO
        unitLoad.weightCalculated = tare.add(content).setScale(3, RoundingMode.HALF_UP)
        unitLoad.weight = unitLoad.weightMeasure ?: unitLoad.weightCalculated
    }

    /**
     * Row 16 override path: applies an operator-set measurement (for instance a scale reading),
     * which beats the calculation once set, or clears it back to the calculation when null.
     * Always re-derives [UnitLoad.weightCalculated] too via [recalculate], so the override is
     * applied against a fresh computation rather than whatever was stored from an earlier
     * mutation.
     */
    fun applyMeasure(unitLoad: UnitLoad, weightMeasure: BigDecimal?) {
        unitLoad.weightMeasure = weightMeasure
        recalculate(unitLoad)
    }
}

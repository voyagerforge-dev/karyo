package com.karyo.inventory.api.spi

import java.math.BigDecimal

/**
 * Read seam for area-level replenishment (R12a, replenishment sprint Task 5): the settled
 * on-hand sum + row count of one product across an arbitrary set of storage locations — the
 * input the area-level scan (R12b, Task 6) compares against
 * [com.karyo.layout.spi.ItemDataAreaView]'s `plannedAmount`/`plannedStocks` thresholds.
 *
 * Explicit `clientId`, strict-equality scoped — same convention (and the same reason) as
 * [com.karyo.layout.spi.ItemDataAreaLookup]: a documented future caller is
 * `ReplenishmentScheduler`'s `@Scheduled` multi-tenant loop, which never primes the ambient
 * `TenantContext` (this sprint's Task 3 Critical lesson).
 */
interface StockSummaryLookup {
    /**
     * Sums [com.karyo.inventory.domain.model.StockUnit.amount] and counts rows for [itemDataId]
     * / [clientId] across [locationIds], restricted to **settled stock**:
     * `state = ON_STOCK(300) AND lockType = 0 AND reservedAmount = 0`.
     *
     * Karyo requires `reservedAmount = 0` as a whole-unit "nothing spoken for" rule because
     * area planning is a replenishment-avoidance signal: counting a partially reserved unit as
     * fully on hand would under-trigger a refill for demand that has already claimed part of the
     * pile. This is the settled-stock contract documented in
     * `docs/functional/replenishment.md#4-area-level-replenishment`. It does NOT check
     * [com.karyo.inventory.domain.model.UnitLoad.lockType] (only
     * the stock unit's own `lockType`) — unlike `StockUnitRepository.findForSelection`'s
     * picking-path convention, this is an on-hand SUMMARY, not a source-selection eligibility
     * gate, and the corpus citation above names only the stock-level lock.
     *
     * [locationIds] empty -> `StockSummary(BigDecimal.ZERO, 0)` WITHOUT issuing any query (an
     * empty SQL `IN ()` is invalid on some dialects, and an area with zero resolved locations
     * has, by definition, zero on-hand — no query needed to know that).
     */
    fun summaryInLocations(itemDataId: Long, clientId: Long, locationIds: Set<Long>): StockSummary
}

/**
 * Settled on-hand sum + row count for one product across a location set, as read by
 * [StockSummaryLookup.summaryInLocations].
 */
data class StockSummary(val totalAmount: BigDecimal, val stockCount: Int)

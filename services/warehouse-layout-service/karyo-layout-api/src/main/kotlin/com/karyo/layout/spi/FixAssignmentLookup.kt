package com.karyo.layout.spi

import java.math.BigDecimal

/** Read seam over fix assignments for replenishment: thresholds + current on-hand at the face. */
interface FixAssignmentLookup {
    fun listForReplenishment(clientId: Long): List<FixAssignmentView>

    /**
     * Distinct `client_id`s owning at least one fix assignment. Used by the R11 replenishment
     * scheduler's tenant loop — deliberately UNSCOPED (a scheduler enumerates all tenants, it
     * doesn't run inside one), and derives the active set from domain data rather than a
     * separate tenant registry because that is the established pattern for scheduler tenant
     * loops in this codebase (see `TenantEnumerator` in `karyo-monitors-core`).
     */
    fun clientIdsWithAssignments(): List<Long>

    /**
     * L7 (locations-layout sprint): picking-facing read of the soft per-pick ceiling
     * (myWMS `FixAssignment.maxPickAmount`). Returns `locationId -> ceiling` for fix rows
     * matching [itemDataId] whose location is one of [locationIds]; only rows with a
     * non-null ceiling are included. myWMS keys the fix by (location, product) — a fix row
     * for a DIFFERENT product on the same location never appears in the result, so callers
     * must always pass the requested product's [itemDataId], never look the map up by
     * location alone.
     */
    fun pickCeilings(clientId: Long, itemDataId: Long, locationIds: Set<Long>): Map<Long, BigDecimal>
}

data class FixAssignmentView(
    val assignmentId: Long,
    val locationId: Long,
    val locationName: String,
    val itemDataId: Long,
    val itemDataNumber: String?,
    val minAmount: BigDecimal?,
    val maxAmount: BigDecimal?,
    val desiredAmount: BigDecimal?,
    /**
     * ON_STOCK-only on-hand at this fix-face (mirrors [com.karyo.inventory.api.spi.StockUnitLookup]'s
     * ON_STOCK-window convention); `null` when the stock lookup itself failed, distinguishable from a
     * genuine zero on-hand -- callers must treat `null` as "unknown", never coerce it to zero.
     */
    val currentAmount: BigDecimal?,
)

package com.karyo.layout.spi

import java.math.BigDecimal

/**
 * Read seam for area-level replenishment (R12a, replenishment sprint Task 5): every
 * [com.karyo.layout.domain.model.ItemDataArea] a tenant has configured, each resolved to the
 * concrete set of [com.karyo.layout.domain.model.StorageLocation] ids its
 * [com.karyo.layout.domain.model.StorageArea] actually covers (area -> clusters -> locations),
 * ready for the area-level scan (R12b, Task 6) to sum on-hand stock across.
 *
 * **Explicit `clientId`, no ambient `TenantContext` read** — this sprint's Task 3 Critical
 * lesson (`ReplenishmentScheduler`'s `@Scheduled` thread runs with an active-but-unprimed
 * request scope, so any ambient `readScope()`/`clientId` read silently resolves to the
 * unassigned default rather than throwing). The R11/R12b scheduler is a documented future
 * caller of this seam, so it is built explicit-`clientId` from the start rather than needing a
 * later CRITICAL fix round like [com.karyo.layout.spi.LocationAreaUsageLookup]'s sibling
 * methods did.
 */
interface ItemDataAreaLookup {
    /**
     * Every [com.karyo.layout.domain.model.ItemDataArea] owned by [clientId], resolved. Bounded
     * query count regardless of how many areas/items are configured — see
     * `DefaultItemDataAreaLookup`'s KDoc for the exact count and why no per-area query loop is
     * needed.
     */
    fun listForReplenishment(clientId: Long): List<ItemDataAreaView>
}

/**
 * One [com.karyo.layout.domain.model.ItemDataArea] row, resolved for the area-level
 * replenishment scan.
 *
 * @param itemDataAreaId the [com.karyo.layout.domain.model.ItemDataArea] row id.
 * @param itemDataId the product id (foreign-module, no FK — same convention the entity itself
 *        uses).
 * @param itemDataNumber the product's `number` (SKU), NOT its display `name` — resolved via a
 *        new explicit-`clientId` [com.karyo.product.spi.ProductLookup.findNumbersByIds] batch
 *        (added alongside this SPI for the same ambient-`TenantContext` reason above; the
 *        existing [com.karyo.product.spi.ProductLookup.findNamesByIds] both reads the wrong
 *        field for this contract's `itemDataNumber` name AND is ambient-scoped, unsafe for the
 *        scheduler caller). Falls back to a synthesized, clearly-not-real `"ITEM-<id>"` label
 *        (never a fabricated real number) on the practically-unreachable race where the product
 *        was deleted after the [com.karyo.layout.domain.model.ItemDataArea] row was created —
 *        `ItemDataAreaService.create` validates the product exists at write time, but nothing
 *        prevents a later delete.
 * @param storageAreaId the [com.karyo.layout.domain.model.StorageArea] id.
 * @param plannedAmount the configured summed-quantity threshold, `null` = not tracked.
 * @param plannedStocks the configured distinct-unit-load threshold, `null` = not tracked.
 * @param clusterLocationIds the UNION of every location id belonging to any cluster the
 *        storage area claims (empty if the area has no clusters assigned, mirroring
 *        [com.karyo.layout.service.StorageAreaService.clustersForAreas]'s "absent/empty means no
 *        clusters" convention rather than treating it as an error).
 */
data class ItemDataAreaView(
    val itemDataAreaId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val storageAreaId: Long,
    val plannedAmount: BigDecimal?,
    val plannedStocks: Int?,
    val clusterLocationIds: Set<Long>,
)

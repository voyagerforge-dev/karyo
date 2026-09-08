package com.karyo.product.spi

import com.karyo.product.dto.ProductResponse
import java.math.BigDecimal

/**
 * In-process product lookup contract. Implemented by product-core and consumed by
 * other modules (e.g. warehouse-layout) instead of a cross-service REST client.
 */
interface ProductLookup {
    fun findById(id: Long): ProductResponse?

    /**
     * Batch id -> product name lookup, tenant-scoped like [findById]. Missing ids (unknown
     * or belonging to another tenant) are simply absent from the result map -- callers treat
     * absence as an honest gap (fall back to the SKU), never fabricate a name.
     */
    fun findNamesByIds(ids: Set<Long>): Map<Long, String>

    /**
     * Batch id -> product `number` (SKU) lookup — deliberately DIFFERENT from [findNamesByIds]
     * (which reads `.name`, the display name). Explicit `clientId`, scoped by strict equality
     * (not the ambient `TenantContext`/`readScope` convention every other method on this SPI
     * uses): added for `com.karyo.layout.spi.ItemDataAreaLookup` (R12a, replenishment sprint
     * Task 5), a documented future caller of `ReplenishmentScheduler`'s `@Scheduled`
     * multi-tenant loop (per this sprint's Task 3 Critical lesson — an ambient read there
     * silently resolves to the unassigned default `clientId = 0`, never throws). Missing ids
     * (unknown or belonging to another tenant) are simply absent from the result map, same
     * honest-gap convention as [findNamesByIds].
     */
    fun findNumbersByIds(ids: Set<Long>, clientId: Long): Map<Long, String>

    /**
     * Batch id -> [ProductMeasures] lookup, tenant-scoped identically to [findNamesByIds] (ONE
     * query; unknown/foreign-tenant ids are simply absent from the result map). Backs the
     * order-level weight/volume computation (WORKLIST row 19, myWMS `PickingBusiness`
     * two-aggregate pattern) -- callers treat a missing entry, or a null field within one, as an
     * honest gap and never fabricate a measure.
     */
    fun findMeasuresByIds(ids: Set<Long>): Map<Long, ProductMeasures>

    /**
     * Explicit-`clientId` overload of [findMeasuresByIds], scoped by strict equality -- same
     * shape and same rationale as [findNumbersByIds]'s explicit-`clientId` overload. Added for
     * [com.karyo.inventory.service.UnitLoadWeightCalculator.recalculateAll] (defect-burndown-5
     * CRITICAL fix, task-3 review): `UnitLoadType` is a shared catalog row with no tenant of its
     * own, so a batch recompute triggered off a type mutation can span unit loads (and their
     * stock's item ids) belonging to MULTIPLE clients in one call. The ambient-scoped
     * [findMeasuresByIds] would silently drop every other client's items under the calling
     * principal's own `TenantContext`, and the calculator treats a missing measure as an honest
     * "unmeasured item, contributes nothing" -- the same gap the ambient read previously turned
     * into a cross-tenant weight corruption bug (another client's unit load persisting a
     * tare-only total). Missing ids (unknown or genuinely belonging to a different client) stay
     * absent from the result map, same honest-gap convention as every other method here.
     */
    fun findMeasuresByIds(ids: Set<Long>, clientId: Long): Map<Long, ProductMeasures>
}

/**
 * A product's physical weight and computed volume (height x width x depth; `null` if any
 * dimension is missing -- see [com.karyo.product.domain.model.ItemData.volume]), as read by
 * [ProductLookup.findMeasuresByIds].
 */
data class ProductMeasures(val weight: BigDecimal?, val volume: BigDecimal?)

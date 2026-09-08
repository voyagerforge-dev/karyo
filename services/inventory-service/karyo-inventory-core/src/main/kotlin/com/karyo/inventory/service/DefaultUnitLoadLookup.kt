package com.karyo.inventory.service

import com.karyo.inventory.api.spi.UnitLoadInfo
import com.karyo.inventory.api.spi.UnitLoadLookup
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.UnitLoad
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.inventory.repository.UnitLoadRepository
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

/**
 * Default in-process [UnitLoadLookup], scoped to the current tenant. Reads straight
 * from [UnitLoadRepository] and returns null (rather than throwing) when the unit load
 * is missing or belongs to another client — the tasks-module putaway observer treats a
 * missing unit load as "skip, log" rather than a hard failure.
 */
@ApplicationScoped
class DefaultUnitLoadLookup(
    private val unitLoadRepository: UnitLoadRepository,
    private val stockUnitRepository: StockUnitRepository,
    private val tenantContext: TenantContext,
) : UnitLoadLookup {

    override fun findById(unitLoadId: Long): UnitLoadInfo? {
        val ul = unitLoadRepository.findById(unitLoadId) ?: return null
        if (!tenantContext.readScope().permits(ul.clientId)) return null
        return toInfo(ul)
    }

    /**
     * Task 3 review CRITICAL-1 (replenishment sprint): unscoped read + strict `clientId` equality
     * — see this method's KDoc on [UnitLoadLookup] for why this must NOT reuse [findById]'s
     * ambient `readScope()` check.
     */
    override fun findById(unitLoadId: Long, clientId: Long): UnitLoadInfo? {
        val ul = unitLoadRepository.findById(unitLoadId) ?: return null
        if (ul.clientId != clientId) return null
        return toInfo(ul)
    }

    /**
     * Sprint C: see [UnitLoadLookup.existsByLabel]. `labelId` is the unit load's label column,
     * and `unit_loads.label_id` carries a GLOBAL UNIQUE constraint (V102) -- labels are not
     * per-tenant, matching myWMS's legacy `UNIQUE(name)` sequence semantics (see
     * `com.karyo.sequence.spi.SequenceSpec`'s KDoc). Filtering by [clientId] here would therefore
     * answer the wrong question: it would report a label as free when another tenant already
     * holds it, and the caller's very next insert would still hit the constraint. [clientId] is
     * kept in the signature for uniformity with the rest of this SPI's explicit-tenant reads,
     * and is deliberately unused.
     */
    @Suppress("UNUSED_PARAMETER")
    override fun existsByLabel(label: String, clientId: Long): Boolean =
        unitLoadRepository.count("labelId = ?1", label) > 0

    private fun toInfo(ul: UnitLoad): UnitLoadInfo {
        // itemDataId/strategyDate (Task 3, locations-layout sprint): resolved from this unit
        // load's OWN content, not the lazy `ul.stockUnits` association (avoids a
        // LazyInitializationException risk across the caller's transaction boundary). A
        // putaway unit load is expected to be single-SKU; zero or multiple distinct items
        // resolve to null/null (honest gap, not a guess) rather than picking arbitrarily.
        //
        // Final-review F1 fix: include INCOMING alongside ON_STOCK. The sole production
        // caller (TaskService.onGoodsReceiptLineReceived, firing AFTER_SUCCESS of
        // GoodsReceiptService.receiveLine) invokes this lookup while the just-received
        // stock is still INCOMING(100) -- markOnStock only promotes it to ON_STOCK(300) at
        // receipt *finish*, which hasn't happened yet. An ON_STOCK-only filter therefore saw
        // zero rows on that path and silently returned itemDataId/strategyDate = null/null,
        // making useAreaStrategyDate/useItemDataArea/nearPickingLocation permanently inert
        // for auto-putaway. This is the unit load's OWN physical content (what is being put
        // away), not warehouse occupancy -- the occupancy reads on the other side of this
        // seam (`StockUnitLookup.occupancyByLocationIds`) stay ON_STOCK-only deliberately and
        // are untouched here.
        val ownStock = stockUnitRepository.findByUnitLoadId(ul.id!!)
            .filter { it.state == StockState.INCOMING.code || it.state == StockState.ON_STOCK.code }
        val distinctItemId = ownStock.map { it.itemDataId }.distinct().singleOrNull()
        val strategyDate = if (distinctItemId != null) {
            ownStock.mapNotNull { it.strategyDate }.minOrNull()
        } else {
            null
        }

        return UnitLoadInfo(
            id = ul.id!!,
            label = ul.labelId,
            locationId = ul.storageLocationId,
            locationName = ul.storageLocationName,
            unitLoadTypeId = ul.unitLoadType.id!!,
            weight = ul.weight ?: BigDecimal.ZERO,
            clientId = ul.clientId,
            itemDataId = distinctItemId,
            strategyDate = strategyDate,
            state = ul.state,
        )
    }
}

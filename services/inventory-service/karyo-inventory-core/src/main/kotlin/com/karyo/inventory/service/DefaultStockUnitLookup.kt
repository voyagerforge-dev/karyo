package com.karyo.inventory.service

import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.ConsolidationStockRef
import com.karyo.inventory.api.spi.ItemStockRef
import com.karyo.inventory.api.spi.LocationOccupantRef
import com.karyo.inventory.api.spi.StockContentRef
import com.karyo.inventory.api.spi.StockLocationRef
import com.karyo.inventory.api.spi.StockOccupancyRef
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.inventory.api.vo.LockType
import com.karyo.inventory.api.vo.StockState
import com.karyo.inventory.domain.model.StockUnit
import com.karyo.inventory.repository.StockUnitRepository
import com.karyo.security.TenantContext
import com.karyo.security.readScope
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Default in-process implementation of [StockUnitLookup], scoped to the current tenant.
 * Replaces the former cross-service REST lookup (GET /api/v1/stock-units?itemDataId=).
 */
@ApplicationScoped
class DefaultStockUnitLookup(
    private val stockService: StockService,
    private val stockUnitRepository: StockUnitRepository,
    private val tenantContext: TenantContext,
) : StockUnitLookup {

    override fun findByItemDataId(itemDataId: Long): List<StockUnitResponse> =
        stockService.findByItemData(itemDataId, tenantContext).map { it.toResponse() }

    override fun findByItemDataId(itemDataId: Long, clientId: Long): List<StockUnitResponse> =
        stockUnitRepository.findByItemDataId(itemDataId, clientId).map { it.toResponse() }

    override fun findByIds(ids: Set<Long>): List<StockUnitResponse> {
        if (ids.isEmpty()) return emptyList()
        val scope = tenantContext.readScope()
        return stockUnitRepository.findByIds(ids)
            .filter { scope.permits(it.clientId) }
            .map { it.toResponse() }
    }

    override fun findByIds(ids: Set<Long>, clientId: Long): List<StockUnitResponse> {
        if (ids.isEmpty()) return emptyList()
        return stockUnitRepository.findByIds(ids)
            .filter { it.clientId == clientId }
            .map { it.toResponse() }
    }

    override fun findByUnitLoadId(unitLoadId: Long): List<StockUnitResponse> =
        stockService.findByUnitLoad(unitLoadId, tenantContext).map { it.toResponse() }

    override fun findByUnitLoadId(unitLoadId: Long, clientId: Long): List<StockUnitResponse> =
        stockUnitRepository.findByUnitLoadId(unitLoadId, clientId).map { it.toResponse() }

    override fun findLocationRefsByIds(ids: Set<Long>): Map<Long, StockLocationRef> {
        if (ids.isEmpty()) return emptyMap()
        val scope = tenantContext.readScope()
        return stockUnitRepository.findByIds(ids)
            .filter { scope.permits(it.clientId) }
            .associate { it.id!! to StockLocationRef(it.id!!, it.unitLoad.labelId, it.unitLoad.storageLocationName) }
    }

    override fun findContentRefsByIds(ids: Set<Long>): Map<Long, StockContentRef> {
        if (ids.isEmpty()) return emptyMap()
        val scope = tenantContext.readScope()
        return stockUnitRepository.findByIds(ids)
            .filter { scope.permits(it.clientId) }
            .associate { it.id!! to StockContentRef(it.id!!, it.bestBefore, it.serialNumber, it.lotNumber) }
    }

    override fun findOwnerClientIdsByIds(ids: Set<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        val scope = tenantContext.readScope()
        return stockUnitRepository.findByIds(ids)
            .filter { scope.permits(it.clientId) }
            .associate { it.id!! to it.clientId }
    }

    override fun occupancyByLocationIds(locationIds: Set<Long>): List<StockOccupancyRef> {
        if (locationIds.isEmpty()) return emptyList()
        val scope = tenantContext.readScope()
        return stockUnitRepository.findOnStockOccupancyByLocationIds(locationIds)
            .filter { scope.permits(it[5] as Long) }
            .map {
                StockOccupancyRef(
                    locationId = it[0] as Long,
                    itemDataId = it[1] as Long,
                    unitLoadId = it[2] as Long?,
                    amount = it[3] as BigDecimal,
                    strategyDate = it[4] as Instant?,
                )
            }
    }

    /**
     * Final-review F3: deliberately UNSCOPED (no [tenantContext] filter), unlike every other
     * read in this class. A location type's lifting-capacity cap is a physical safety
     * constraint on the rack/field/section, not a per-owner one — a pallet's weight presses
     * down on the shared structure regardless of which goods-owner it belongs to. Scoping this
     * read would let another tenant's pallets on the same physical group silently vanish from
     * the load calculation for an OWNER-scoped caller, under-counting real weight and risking
     * an overload. Mirrors the same "shared physical/layout config, not tenant data" reasoning
     * already applied to `findGroupMembersByAreaIds` on the layout side of this seam.
     */
    override fun grossWeightByLocationIds(locationIds: Set<Long>): Map<Long, BigDecimal> {
        if (locationIds.isEmpty()) return emptyMap()
        return stockUnitRepository.findOnStockUnitLoadWeightByLocationIds(locationIds)
            .groupBy({ it[0] as Long }, { it[2] as BigDecimal? })
            .mapValues { (_, weights) -> weights.fold(BigDecimal.ZERO) { acc, w -> acc + (w ?: BigDecimal.ZERO) } }
    }

    override fun lotNumbersAtLocation(locationId: Long, clientId: Long): Set<String> =
        stockUnitRepository.findLotNumbersByLocation(locationId, clientId)
            .filterNot { it.isBlank() }
            .toSet()

    /**
     * Deliberately UNSCOPED -- no [tenantContext] filter, matching [StockUnitLookup.
     * occupantsByLocationIds]'s KDoc ruling: the finder's client-mixing pass needs to see
     * every owner's occupancy, not just the caller's.
     */
    override fun occupantsByLocationIds(locationIds: Set<Long>): List<LocationOccupantRef> {
        if (locationIds.isEmpty()) return emptyList()
        return stockUnitRepository.findOccupantsByLocationIds(locationIds)
            .map {
                LocationOccupantRef(
                    locationId = it[0] as Long,
                    clientId = it[1] as Long,
                    itemDataId = it[2] as Long,
                )
            }
    }

    override fun itemStocksByLocationIds(itemDataId: Long, locationIds: Set<Long>, clientId: Long): List<ItemStockRef> {
        if (locationIds.isEmpty()) return emptyList()
        return stockUnitRepository.findItemStocksByLocationIds(itemDataId, locationIds, clientId)
            .map {
                ItemStockRef(
                    stockUnitId = it[0] as Long,
                    locationId = it[1] as Long,
                    lotNumber = it[2] as String?,
                )
            }
    }

    override fun fifoConsolidationRefs(
        itemDataId: Long,
        lotNumber: String?,
        bestBefore: LocalDate?,
        clientId: Long,
        limit: Int,
    ): List<ConsolidationStockRef> =
        stockUnitRepository.findFifoConsolidationCandidates(itemDataId, lotNumber, bestBefore, clientId, limit)
            .map {
                ConsolidationStockRef(
                    stockUnitId = it[0] as Long,
                    locationId = it[1] as Long,
                    locationName = it[2] as String,
                )
            }

    private fun StockUnit.toResponse() = StockUnitResponse(
        id = id!!,
        itemDataId = itemDataId,
        itemDataNumber = itemDataNumber,
        // Intentionally not wired: no current consumer of this SPI path reads itemDataName.
        // Wire a batch ProductLookup here if a future consumer needs it.
        itemDataName = null,
        amount = amount,
        reservedAmount = reservedAmount,
        availableAmount = availableAmount,
        serialNumber = serialNumber,
        lotNumber = lotNumber,
        packagingUnitId = packagingUnitId,
        bestBefore = bestBefore,
        state = state,
        stateName = StockState.fromCode(state).name,
        lockType = lockType,
        lockTypeName = LockType.fromCode(lockType).name,
        strategyDate = strategyDate,
        unitLoadId = unitLoad.id!!,
        unitLoadLabel = unitLoad.labelId,
        locationId = unitLoad.storageLocationId,
        locationName = unitLoad.storageLocationName,
        created = created,
        modified = modified,
        // Intentionally not wired: no current consumer of this SPI path reads
        // supplier/ASN/received. Wire a batch GoodsReceiptLookup here if a future
        // consumer needs it.
        supplierName = null,
        sourceAsn = null,
        receivedAt = null,
        aggregateStocks = unitLoad.unitLoadType.aggregateStocks,
    )
}

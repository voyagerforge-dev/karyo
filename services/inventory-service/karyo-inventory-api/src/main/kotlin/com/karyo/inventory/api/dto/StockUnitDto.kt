package com.karyo.inventory.api.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class StockUnitResponse(
    val id: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val itemDataName: String?,
    val amount: BigDecimal,
    val reservedAmount: BigDecimal,
    val availableAmount: BigDecimal,
    val serialNumber: String?,
    val lotNumber: String?,
    /** A5: ID-only reference to the product module's PackagingUnit, validated at creation via PackagingUnitLookup. */
    val packagingUnitId: Long?,
    val bestBefore: LocalDate?,
    val state: Int,
    val stateName: String,
    val lockType: Int,
    val lockTypeName: String,
    val strategyDate: Instant?,
    val unitLoadId: Long,
    val unitLoadLabel: String,
    val locationId: Long,
    val locationName: String,
    val created: Instant,
    val modified: Instant,
    /** Supplier via the GoodsReceiptLookup batch join; null when the unit was not received through a GR (honest gap). */
    val supplierName: String?,
    /** Source ASN number via the same lookup; null = honest gap (blind receipt or not GR-sourced). */
    val sourceAsn: String?,
    /** Goods-receipt `created` timestamp (ISO), via the same lookup; null = honest gap. */
    val receivedAt: String?,
    /** The unit load's UnitLoadType.aggregateStocks -- true = loose/aggregated, false = discrete LPN-tracked. */
    val aggregateStocks: Boolean,
)

data class CreateStockUnitRequest(
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val unitLoadId: Long,
    val lotNumber: String? = null,
    val serialNumber: String? = null,
    /** A5: ID-only reference to the product module's PackagingUnit; validated at creation via PackagingUnitLookup. */
    val packagingUnitId: Long? = null,
    val bestBefore: LocalDate? = null,
    val state: Int = 0,
    val activityCode: String? = null,
)

data class AdjustStockRequest(
    val newAmount: BigDecimal,
    val activityCode: String,
    val reason: String? = null,
)

data class ReserveStockRequest(
    val amount: BigDecimal,
    val correlationId: String,
)

data class ReserveStockResponse(
    val stockUnitId: Long,
    val reservedAmount: BigDecimal,
    val newAvailableAmount: BigDecimal,
)

data class SetLockRequest(
    val lockType: Int,
    val reason: String? = null,
)

/** Row 15 reclassify-in-place. A null id clears the classification. */
data class ChangePackagingUnitRequest(val packagingUnitId: Long? = null)

data class TransferStockRequest(
    val targetUnitLoadId: Long,
    val amount: BigDecimal,
    val activityCode: String,
)

/**
 * Row 13. A null [amount] means "whatever is currently reserved on the source" (a full
 * transfer); an explicit amount less than the source's whole reservedAmount is a partial
 * transfer, refused whenever an order-line or open-pick reference still exists on the source.
 */
data class TransferReservationRequest(
    val targetStockUnitId: Long,
    val amount: BigDecimal? = null,
)

data class StockSelectionResponse(
    val stocks: List<com.karyo.inventory.api.vo.PickStockResult>,
    val totalAvailable: BigDecimal,
    val fullyFulfilled: Boolean,
)

/**
 * Σ on-hand amount of one item at one location. [amount] counts ON_STOCK stock only and is the
 * physical total, not the available one — see `StockService.readAmount`.
 */
data class StockAmountResponse(
    val itemDataId: Long,
    val locationId: Long,
    val amount: BigDecimal,
)

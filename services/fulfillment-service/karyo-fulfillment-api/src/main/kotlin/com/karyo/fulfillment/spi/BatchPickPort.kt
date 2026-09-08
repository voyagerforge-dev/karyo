package com.karyo.fulfillment.spi

import java.math.BigDecimal

/** Wave-shaped pick generation + wave pick queries. Explicit clientId everywhere. */
interface BatchPickPort {
    /**
     * Generate this wave's PickOrders from the members' existing reservations.
     * COMPLETE slices (reservation covers the full source stock unit) become per-order
     * PickOrders; PICK slices become cross-order batch PickOrders (deliveryOrderId null,
     * batchZone from PickZoneLookup). Mode COMPLETE_ONLY drops PICK slices (returned as
     * leftovers for shortage handling); PICK_ONLY sends everything down the batch path.
     */
    fun generateForWave(request: WavePickRequest): WavePickResult
    /** Live pick stats for progress derivation. */
    fun waveStats(waveId: Long, clientId: Long): WavePickStats
    /** Cancel this wave's still-open PickOrders (existing cancel machinery). Returns count. */
    fun cancelOpenForWave(waveId: Long, clientId: Long): Int
    /** True when every pick of every wave PickOrder is terminal (PICKED or CANCELED). */
    fun allTerminal(waveId: Long, clientId: Long): Boolean
    /** Open (non-terminal) pick count for the given delivery orders (consolidation READY guard). */
    fun openPicksForOrders(deliveryOrderIds: List<Long>, clientId: Long): Int

    /**
     * Sort-station read (Bulk Allocation Sprint A): every PICKED pick that (a) references one of
     * [lineIds] and (b) sits on a BATCH PickOrder (`deliveryOrderId == null`, `waveId` set), as
     * one slice each. Per-order PickOrders are excluded: they never pass the put wall. Empty
     * [lineIds] returns empty. The cart is the batch PickOrder's `targetUnitLoadId`; the cart
     * stock unit is the pick's `targetStockUnitId` (where the picked quantity landed).
     */
    fun pickedByLines(lineIds: Collection<Long>, clientId: Long): List<PickedLineSlice>

    /** The batch PickOrder whose pick container is [unitLoadId], with its PICKED slices, or null. */
    fun cartByUnitLoad(unitLoadId: Long, clientId: Long): BatchCart?
}

data class WavePickRequest(
    val waveId: Long, val clientId: Long, val wavePickMode: String,
    val deliveryOrderIds: List<Long>,
)
data class WavePickResult(
    val pickOrderIds: List<Long>,
    val batchPickOrderIds: List<Long>,
    /** PICK-path slices dropped by COMPLETE_ONLY, for the wave's shortage handling. */
    val droppedEachesLines: List<DroppedLine>,
)
data class DroppedLine(val deliveryOrderLineId: Long?, val itemDataId: Long, val amount: BigDecimal)
data class WavePickStats(
    val totalPicks: Int, val pickedPicks: Int, val openPickOrders: Int, val totalPickOrders: Int,
)

data class PickedLineSlice(
    val deliveryOrderLineId: Long,
    val pickId: Long,
    val pickOrderId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    /** Actual lot (`pickedLotNumber`), falling back to the planned lot; null when unlotted. */
    val lotNumber: String?,
    val pickedAmount: BigDecimal,
    val cartUnitLoadId: Long,
    val cartStockUnitId: Long?,
)

data class BatchCart(
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val waveId: Long,
    val state: Int,
    val bulk: Boolean = false,
    val unitLoadId: Long,
    val slices: List<PickedLineSlice>,
)

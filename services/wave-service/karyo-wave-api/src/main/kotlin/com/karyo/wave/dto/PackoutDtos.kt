package com.karyo.wave.dto

import java.math.BigDecimal

/**
 * Bulk Allocation Sprint C: the pack-out view of one READY consolidation group -- the group
 * shipment, its containers, and the live "sorted vs packed" ledger the packer works against.
 *
 * Everything here is DERIVED on every read (Sprint A's put-wall picture minus
 * `ConsolidationPackPort.packedByPicks`, the per-SLICE consumption ledger -- the per-line
 * `packedByLines` feeds the wave-detail group counter, not [PackoutItem.packed]); wave-core stores
 * no pack-out counter of its own.
 */
data class PackoutContainerLine(
    val deliveryOrderId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val amount: BigDecimal,
)

/** [state] is `OPEN` while the container still accepts lines, `CLOSED` once its stock is PACKED. */
data class PackoutContainer(
    val id: Long,
    val shippingUnitNumber: String,
    val unitLoadId: Long,
    val state: String,
    val type: String,
    val weight: BigDecimal,
    val lines: List<PackoutContainerLine>,
)

/** One (SKU, lot) the group holds: [sorted] at the put wall, [packed] into containers so far. */
data class PackoutItem(
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val sorted: BigDecimal,
    val packed: BigDecimal,
    val remaining: BigDecimal,
)

/**
 * [memberOrderIds] are the group's members WITH sorted content: a zero-content member (every
 * wave pick short-confirmed to nothing) is never put on the group shipment, and surfaces on
 * `WaveDetailResponse.unfilledOrders` instead. [complete] mirrors the shipment having reached
 * PACKED.
 */
data class PackoutResponse(
    val shipmentId: Long,
    val shipmentNumber: String,
    val shipmentState: Int,
    val groupId: Long,
    val sortSlot: String,
    val destinationKey: String,
    val memberOrderIds: List<Long>,
    val items: List<PackoutItem>,
    val containers: List<PackoutContainer>,
    val complete: Boolean,
)

/** [unitLoadId] adopts a scanned, empty LPN as the container; null mints a fresh one. */
data class OpenContainerBody(val unitLoadId: Long? = null, val type: String = "CARTON")

/**
 * "Put [amount] of [itemDataNumber] in this container." Which member orders (and which pick
 * slices) that quantity is drawn from is the allocator's decision, never the caller's --
 * allocation order is prio DESC, order created ASC, line id ASC.
 */
data class AddContainerLineBody(
    val itemDataNumber: String,
    val lotNumber: String? = null,
    val amount: BigDecimal = BigDecimal.ONE,
)

data class CloseContainerBody(val weight: BigDecimal)

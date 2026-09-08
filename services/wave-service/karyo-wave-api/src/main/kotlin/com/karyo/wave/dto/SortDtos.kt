package com.karyo.wave.dto

import java.math.BigDecimal

/** One consolidation group's live sort counters, as seen from either the whole wave (status)
 *  or one cart (resolveCart) -- the wave-core `SortStationService`'s private `groupViews` helper
 *  narrows to one cart's lines when called from `resolveCart`. */
data class SortGroupView(
    val groupId: Long,
    val sortSlot: String,
    val destinationKey: String,
    val state: String,
    val picked: BigDecimal,
    val sorted: BigDecimal,
    val remaining: BigDecimal,
)

/** One SKU+lot still owed by the batch cart's picked slices, net of what has already been sorted. */
data class SortCartItem(
    val itemDataId: Long,
    val itemDataNumber: String,
    val lotNumber: String?,
    val picked: BigDecimal,
    val sorted: BigDecimal,
    val remaining: BigDecimal,
)

data class SortCartResponse(
    val waveId: Long,
    val waveNumber: String,
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val unitLoadId: Long,
    val groups: List<SortGroupView>,
    val items: List<SortCartItem>,
)

data class SortScanRequest(
    val cartUnitLoadId: Long,
    val itemDataNumber: String,
    val lotNumber: String? = null,
    val amount: BigDecimal = BigDecimal.ONE,
)

data class SortScanResponse(
    val scanId: Long,
    val groupId: Long,
    val sortSlot: String,
    val destinationKey: String,
    val groupState: String,
    val amount: BigDecimal,
    val cartRemainingForItem: BigDecimal,
)

data class SortStatusResponse(val waveId: Long, val groups: List<SortGroupView>)

package com.karyo.orders.dto

import java.math.BigDecimal

/**
 * Result of release / retry-reservation: the updated order plus a shortage report.
 * [shortages] is empty when every line was fully reserved (all lines PROCESSABLE,
 * order PROCESSABLE); otherwise it lists each line still short (line PENDING,
 * order stays RELEASED).
 */
data class DeliveryOrderReleaseResponse(
    val order: DeliveryOrderResponse,
    val shortages: List<LineShortage>,
)

data class LineShortage(
    val lineId: Long,
    val lineNumber: Int,
    val itemDataId: Long,
    val itemDataNumber: String,
    val requestedAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val shortfall: BigDecimal,
)

package com.karyo.crossdock.dto

import java.math.BigDecimal
import java.time.Instant

/** REST response shape for `GET /api/v1/cross-dock-orders` and `GET /{id}` (Task 7). */
data class CrossDockOrderResponse(
    val id: Long,
    val orderNumber: String,
    val state: Int,
    val stateName: String,
    val crossDockType: String,
    val goodsReceiptLineId: Long,
    val deliveryOrderLineId: Long,
    val itemDataId: Long,
    val amount: BigDecimal,
    val unitLoadId: Long?,
    val stockUnitId: Long?,
    val transportOrderId: Long?,
    val stagingLocationId: Long?,
    val stagingDeadline: Instant?,
    val clientId: Long,
    val created: Instant,
)

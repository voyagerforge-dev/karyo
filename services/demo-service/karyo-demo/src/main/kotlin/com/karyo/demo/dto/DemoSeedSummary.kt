package com.karyo.demo.dto

/** Gate-discovery probe response ([DemoResource.status]) -- only ever returned when enabled=true. */
data class DemoStatusResponse(val enabled: Boolean)

data class DemoSeedSummary(
    val locations: Int,
    val skus: Int,
    val orders: Int,
    val picks: Int,
    val shipments: Int,
    val counts: Int,
    val goodsReceipts: Int,
    val transportOrders: Int,
    val alertsTripped: List<String>,
)

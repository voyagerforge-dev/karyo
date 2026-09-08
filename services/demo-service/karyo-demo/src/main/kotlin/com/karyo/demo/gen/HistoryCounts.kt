package com.karyo.demo.gen

/**
 * Row counts produced by one [HistoryGenerator.generate] run — surfaced in the seed summary
 * (Task 7) so an operator/tour can see how much backdated activity was built.
 */
data class HistoryCounts(
    val orders: Int,
    val picks: Int,
    val shipments: Int,
    val goodsReceipts: Int,
    val putawayBacklog: Int,
)

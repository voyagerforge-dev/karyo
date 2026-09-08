package com.karyo.simulation.spi

/**
 * Hybrid seam (mirrors v1.7b's ForecastModel / v1.7c's SlottingStrategy): a deterministic
 * continuous-review (s, Q) lost-sales replay ships now (key "lost-sales"); backorder or
 * stochastic/Monte-Carlo models implement the same interface later.
 */
interface ReorderPolicySimulator {
    val key: String
    fun simulate(input: ReorderSimInput): ReorderSimResult
}

/**
 * One SKU's backtest inputs: the dense daily demand series to replay (one entry per day
 * over the history window), the replenishment lead time, both policies' reorder points
 * (sBase = naive / sSug = with safety stock) and the shared order quantity.
 */
data class ReorderSimInput(
    val demandSeries: List<Double>,
    val leadTimeDays: Int,
    val sBase: Int,
    val sSug: Int,
    val orderQty: Int,
)

/** Outcome of replaying one policy over the demand series. */
data class PolicyOutcome(
    val stockoutDays: Int,
    val fillRate: Double,
    val avgOnHand: Double,
)

/** Both policies' outcomes for one SKU (same demand series, same Q, differing reorder point). */
data class ReorderSimResult(
    val baseline: PolicyOutcome,
    val suggested: PolicyOutcome,
)

package com.karyo.replenishment.spi

import java.math.BigDecimal

/** Decides WHETHER a fix-face needs replenishing. Default = on-hand below min.
 *  (WHICH source to pull is the inventory ReplenishmentSourceSelector seam.) */
interface ReplenishmentStrategy {
    /** Lower runs first; built-in uses MAX so any custom strategy wins. */
    val priority: Int
    /** The strategy name this answers to (built-in: "MIN_MAX"). */
    val name: String
    fun needsReplenishment(
        currentAmount: BigDecimal,
        minAmount: BigDecimal?,
        maxAmount: BigDecimal?,
        desiredAmount: BigDecimal?,
    ): Boolean
}

package com.karyo.replenishment.service

import com.karyo.replenishment.spi.ReplenishmentStrategy
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal

@ApplicationScoped
class DefaultReplenishmentStrategy : ReplenishmentStrategy {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = "MIN_MAX"
    override fun needsReplenishment(
        currentAmount: BigDecimal,
        minAmount: BigDecimal?,
        maxAmount: BigDecimal?,
        desiredAmount: BigDecimal?,
    ): Boolean = minAmount != null && currentAmount < minAmount
}

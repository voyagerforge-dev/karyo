package com.karyo.inventory.api.vo

import java.math.BigDecimal

sealed class StockOperationResult {
    data class Success(val stockUnitId: Long, val newAmount: BigDecimal) : StockOperationResult()
    data class InsufficientStock(val available: BigDecimal, val requested: BigDecimal) : StockOperationResult()
    data class StockLocked(val lockType: Int) : StockOperationResult()
    data class StockNotFound(val stockUnitId: Long) : StockOperationResult()
    data class ConcurrencyConflict(val message: String) : StockOperationResult()
}

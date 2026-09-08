package com.karyo.reporting.api.v1.dto

import java.math.BigDecimal

/** One row of the volume-by-category breakdown — pick volume grouped by `item_data.trade_group`. */
data class CategoryVolumeResponse(
    val category: String,
    val volume: BigDecimal,
    val lineCount: Long,
)

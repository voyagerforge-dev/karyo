package com.karyo.inventory.api.dto

import java.math.BigDecimal
import java.time.Instant

data class UnitLoadTypeResponse(
    val id: Long,
    val name: String,
    val usages: String?,
    val aggregateStocks: Boolean,
    val height: BigDecimal?,
    val width: BigDecimal?,
    val depth: BigDecimal?,
    val liftingCapacity: BigDecimal?,
    val weight: BigDecimal?,
    val manageEmpties: Boolean,
    val created: Instant,
    val modified: Instant,
)

data class CreateUnitLoadTypeRequest(
    val name: String,
    val usages: String? = null,
    val aggregateStocks: Boolean = false,
    val height: BigDecimal? = null,
    val width: BigDecimal? = null,
    val depth: BigDecimal? = null,
    val liftingCapacity: BigDecimal? = null,
    val weight: BigDecimal? = null,
    val manageEmpties: Boolean = false,
)

/** Row 16: full-representation update for a unit load type. Carries every mutable field. */
data class UpdateUnitLoadTypeRequest(
    val name: String,
    val usages: String? = null,
    val aggregateStocks: Boolean = false,
    val height: BigDecimal? = null,
    val width: BigDecimal? = null,
    val depth: BigDecimal? = null,
    val liftingCapacity: BigDecimal? = null,
    val weight: BigDecimal? = null,
    val manageEmpties: Boolean = false,
)

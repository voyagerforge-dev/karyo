package com.karyo.layout.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

/**
 * Create/update payload for a `TypeCapacityConstraint` row (locations-layout sprint Task 4).
 * `allocation` bounds mirror the NUMERIC(5,2) column: exclusive-zero lower bound (a 0%
 * constraint is meaningless — use delete instead) up to 999.99 (myWMS oversize/multi-position
 * rows; see `LocationFinderService`'s KDoc on the multi-position placement rule Karyo
 * deliberately does not implement).
 * `unitLoadTypeId` is a FOREIGN MODULE id (inventory) — no lookup SPI exists to validate it
 * (see `TypeCapacityConstraintService`), so it is accepted as-is.
 */
data class CreateTypeCapacityConstraintRequest(
    @field:NotNull val locationTypeId: Long,
    @field:NotNull val unitLoadTypeId: Long,
    @field:DecimalMin(value = "0", inclusive = false)
    @field:DecimalMax(value = "999.99")
    val allocation: BigDecimal = BigDecimal("100"),
    val orderIndex: Int = 0,
)

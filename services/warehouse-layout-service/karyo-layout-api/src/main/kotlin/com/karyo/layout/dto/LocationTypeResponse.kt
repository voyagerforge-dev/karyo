package com.karyo.layout.dto

import java.math.BigDecimal

data class LocationTypeResponse(
    val id: Long,
    val name: String,
    val height: BigDecimal?,
    val width: BigDecimal?,
    val depth: BigDecimal?,
    val liftingCapacity: BigDecimal?,
    /** L4: group lifting capacities — see [CreateLocationTypeRequest]'s KDoc. */
    val fieldLiftingCapacity: BigDecimal?,
    val sectionLiftingCapacity: BigDecimal?,
    val created: String,
    val modified: String,
)

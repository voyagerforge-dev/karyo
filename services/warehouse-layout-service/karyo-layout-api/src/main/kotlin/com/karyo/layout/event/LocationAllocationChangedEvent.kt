package com.karyo.layout.event

import java.math.BigDecimal

data class LocationAllocationChangedEvent(
    val locationId: Long,
    val locationName: String,
    val oldAllocation: BigDecimal,
    val newAllocation: BigDecimal,
    val clientId: Long,
)

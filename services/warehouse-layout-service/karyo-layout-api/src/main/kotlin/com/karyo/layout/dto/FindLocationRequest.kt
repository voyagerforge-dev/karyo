package com.karyo.layout.dto

import java.math.BigDecimal

/** Internal REST body for the putaway location finder (future external/cross-host callers). */
data class FindLocationRequest(
    val unitLoadId: Long,
    val unitLoadTypeId: Long? = null,
    val weight: BigDecimal = BigDecimal.ZERO,
    val clientId: Long? = null,
    /** Reservation correlation id (caller's transport order id); defaults to unitLoadId. */
    val reservationKey: Long? = null,
    val storageStrategyId: Long? = null,
    val preferredZoneId: Long? = null,
    val pickingOnly: Boolean = false,
)

/** Internal REST result mirroring [com.karyo.layout.spi.LocationFinderResult]. */
data class FindLocationResponse(
    val found: Boolean,
    val locationId: Long? = null,
    val locationName: String? = null,
    val reason: String? = null,
)

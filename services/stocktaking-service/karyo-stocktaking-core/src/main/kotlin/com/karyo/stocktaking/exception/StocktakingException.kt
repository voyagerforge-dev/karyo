package com.karyo.stocktaking.exception

import com.karyo.common.exception.KaryoException

sealed class StocktakingException(message: String) : KaryoException(message) {

    class NotFound(entityType: String, identifier: Any) :
        StocktakingException("$entityType not found: $identifier")

    class LocationLocked(locationId: Long, detail: String) :
        StocktakingException("Location $locationId is already locked: $detail")

    class ReservedStock(locationId: Long) :
        StocktakingException("Location $locationId has reserved stock; resolve reservations before counting")

    class InvalidState(detail: String) :
        StocktakingException("Invalid state: $detail")

    class InvalidCount(detail: String) :
        StocktakingException("Invalid count: $detail")
}

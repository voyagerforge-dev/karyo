package com.karyo.orders.spi

import com.karyo.orders.dto.DeliveryOrderResponse

/**
 * Extension point consulted when a DeliveryOrder is released.
 *
 * Implementations are discovered via CDI (`Instance<OrderReleaseValidator>`) — drop a
 * `@ApplicationScoped` bean implementing this interface into an extension JAR and it
 * is consulted automatically. ALL discovered implementations run; release proceeds
 * only when every validator returns an empty list. Any violation aborts the release
 * with a 409 carrying the violation messages.
 *
 * No default implementation ships with the core — zero implementations means the
 * release always passes validation.
 */
interface OrderReleaseValidator {

    /** Returns the violations preventing release of [order]; empty list = pass. */
    fun validate(order: DeliveryOrderResponse): List<OrderReleaseViolation>
}

data class OrderReleaseViolation(
    val code: String,
    val message: String,
)

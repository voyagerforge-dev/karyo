package com.karyo.fulfillment.spi

/**
 * Orders→fulfillment WRITE seam (the reciprocal of `OrderProgressionPort`, which is
 * fulfillment→orders). Declared in fulfillment-api and implemented by fulfillment-core, so
 * orders-core keeps its existing single Gradle edge to fulfillment-api.
 *
 * Force-finishes every non-terminal PickOrder referencing a delivery order: open picks are
 * CANCELED with their outstanding reservations released by the pick machinery itself
 * (`StockPicker.releaseUnpickedReservation`), while a pick order that keeps genuinely-PICKED picks
 * lands on PICKED — goods already moved into a pick container are NOT un-picked here; ops disposes
 * of them through extinguish/return.
 *
 * Operator claim guards are superseded deliberately: a delivery-order cancel carries order-write
 * authority over its own work, so it must not be blockable by whoever happens to hold the pick
 * order (the REST pick-order cancel keeps its guards — see `PickLifecycleService.cancelOrder`).
 */
interface PickCancelPort {
    /** @return the number of pick orders force-finished (0 when the order was never released to picking). */
    fun cancelOpenWorkForDeliveryOrder(deliveryOrderId: Long, clientId: Long): Int
}

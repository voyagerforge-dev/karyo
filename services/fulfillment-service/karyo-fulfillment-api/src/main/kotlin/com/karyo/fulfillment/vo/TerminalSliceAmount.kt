package com.karyo.fulfillment.vo

import java.math.BigDecimal

/**
 * Per-(line, source stock unit) sum of `plannedAmount` over TERMINAL picks (PICKED(600) or
 * CANCELED(800)).
 *
 * A terminal pick's stock-side reservation is fully resolved: the picked portion was consumed by
 * `StockPicker.pickStock` at confirm, and any outstanding portion was released
 * (`releaseUnpickedReservation`) at confirm-shortfall or cancel. Orders subtracts these sums from
 * its own recorded `OrderLineReservation` slices to find what is STILL reserved on the order's
 * behalf, instead of re-releasing already-resolved slices (the I1 cross-order double-release).
 */
data class TerminalSliceAmount(
    val deliveryOrderLineId: Long,
    val sourceStockUnitId: Long,
    val plannedAmount: BigDecimal,
)

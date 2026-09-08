package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Turns a pick container's confirmed contents into shipping units. The resolver tries a matching
 * [name] first, then the remaining strategies in ascending [priority] until one answers. Returning
 * null defers to that fallback chain; the built-in OneToOne strategy runs last.
 * [PackoutResult.shippingUnits] supports multiple units, and [PackoutResult.complete] tells the
 * service whether packing is done, allowing incremental packing across calls.
 */
interface PackoutStrategy {
    /** Lower runs first; built-in OneToOne uses a high value so any custom strategy wins. */
    val priority: Int

    /** The OrderStrategy.packoutStrategy name this strategy answers to (built-in: "ONE_TO_ONE"). */
    val name: String

    /** Pack the [context]'s pick container, or null to defer to the next strategy. */
    fun pack(context: PackoutContext): PackoutResult?
}

data class PackoutContext(
    val shipmentId: Long,
    val deliveryOrderId: Long,
    val pickContainerUnitLoadId: Long,
    val clientId: Long,
    val weight: BigDecimal,
    val type: String,
    val packoutStrategyName: String,
    val picks: List<PackPick>,
)

data class PackPick(
    val pickId: Long,
    val itemDataId: Long,
    val itemDataNumber: String,
    val pickedAmount: BigDecimal,
    val lotNumber: String?,
    val sourceStockUnitId: Long?,
)

data class PackoutResult(
    val shippingUnits: List<PlannedShippingUnit>,
    val complete: Boolean,
)

data class PlannedShippingUnit(
    val type: String,
    val weight: BigDecimal,
    val unitLoadId: Long?,
    val lines: List<PlannedShippingUnitLine>,
)

data class PlannedShippingUnitLine(
    val itemDataId: Long,
    val itemDataNumber: String,
    val amount: BigDecimal,
    val sourcePickId: Long?,
    val lotNumber: String?,
)

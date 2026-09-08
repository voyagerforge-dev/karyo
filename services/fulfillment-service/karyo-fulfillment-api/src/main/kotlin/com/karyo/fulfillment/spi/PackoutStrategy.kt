package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Strategy-SPI (ADR-036): turns a pick container's confirmed contents into shipping unit(s). v1.3 ships
 * only OneToOnePackout (the container -> one ShippingUnit + weight); CartonizationPackout (re-pack eaches
 * -> N boxes) and ConsolidationPackout are future beans. Priority-ordered, first-non-null-wins, built-in
 * (OneToOne) runs last. [PackoutResult.shippingUnits] is a LIST and [PackoutResult.complete] tells the
 * service whether packing is done (so future strategies can pack box-by-box across multiple calls).
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

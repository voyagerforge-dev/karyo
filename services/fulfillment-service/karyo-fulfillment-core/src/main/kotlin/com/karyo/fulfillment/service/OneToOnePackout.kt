package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.PackPick
import com.karyo.fulfillment.spi.PackoutContext
import com.karyo.fulfillment.spi.PackoutResult
import com.karyo.fulfillment.spi.PackoutStrategy
import com.karyo.fulfillment.spi.PlannedShippingUnit
import com.karyo.fulfillment.spi.PlannedShippingUnitLine
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in packout: the pick container becomes ONE ShippingUnit (captured weight + type), one line per
 * confirmed pick (preserving sourcePickId + lot). complete=true (packing done in one call). Lowest
 * priority; answers "ONE_TO_ONE". Future CartonizationPackout/ConsolidationPackout pre-empt by priority
 * + the packoutStrategy knob.
 */
@ApplicationScoped
class OneToOnePackout : PackoutStrategy {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = "ONE_TO_ONE"

    override fun pack(context: PackoutContext): PackoutResult = PackoutResult(
        shippingUnits = listOf(
            PlannedShippingUnit(
                type = context.type,
                weight = context.weight,
                unitLoadId = context.pickContainerUnitLoadId,
                lines = context.picks.map { p: PackPick ->
                    PlannedShippingUnitLine(p.itemDataId, p.itemDataNumber, p.pickedAmount, p.pickId, p.lotNumber)
                },
            ),
        ),
        complete = true,
    )
}

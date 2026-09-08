package com.karyo.fulfillment.service

import com.karyo.events.outbox.OutboxService
import com.karyo.fulfillment.domain.event.PickShortfallReportedEvent
import com.karyo.fulfillment.spi.ShortfallContext
import com.karyo.fulfillment.spi.ShortfallResolution
import com.karyo.fulfillment.spi.ShortfallStrategy
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Built-in terminal handler: partial-ship. The uncovered remainder is accepted as short — a
 * shortage report is written to the outbox and the order is allowed to complete. Lowest priority
 * (runs last); answers the "PARTIAL_SHIP" name. Future PENDING-escalation/auto-recovery strategies
 * pre-empt by priority + the shortfallStrategy knob.
 */
@ApplicationScoped
class PartialShipShortfallStrategy(
    private val outboxService: OutboxService,
) : ShortfallStrategy {
    override val priority: Int = Int.MAX_VALUE
    override val name: String = "PARTIAL_SHIP"

    override fun handle(context: ShortfallContext): ShortfallResolution {
        outboxService.publish(
            "Pick", context.pickId, "PickShortfallReported",
            PickShortfallReportedEvent(
                pickId = context.pickId,
                deliveryOrderId = context.deliveryOrderId,
                deliveryOrderLineId = context.deliveryOrderLineId,
                itemDataId = context.itemDataId,
                shortfall = context.remainder,
                resolution = "PARTIAL_SHIP",
                clientId = context.clientId,
                occurredAt = Instant.now(),
            ),
            context.clientId,
        )
        return ShortfallResolution(shortShipped = context.remainder)
    }
}

package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.PickReleasePort
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default in-process implementation of [PickReleasePort] over [PickOrderService.releaseToPicking],
 * placed here like [DefaultOpenPickGuard]/[DefaultPickRollupLookup] (fulfillment-core's own port
 * implementations live in this package, not `messaging`). Delegates to the explicit-`clientId`
 * overload so the caller (the streaming engine's `@Scheduled` scheduler, Task 5) never depends on
 * the ambient `TenantContext`.
 */
@ApplicationScoped
class DefaultPickReleasePort(private val pickOrderService: PickOrderService) : PickReleasePort {

    override fun releaseToPicking(deliveryOrderId: Long, clientId: Long): List<Long> =
        pickOrderService.releaseToPicking(deliveryOrderId, null, clientId).map { it.id!! }
}

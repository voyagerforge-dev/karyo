package com.karyo.orders.service

import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.exception.OrderException
import com.karyo.orders.vo.OrderState
import jakarta.enterprise.context.ApplicationScoped

/**
 * Row 10 operator-claim guards, split out of [OrderService.claim] into their own tiny class (fix
 * round 1) rather than back into [OrderService] itself: moving only the location functions out of
 * the former `DeliveryOrderValidator` grab-bag left [OrderService] at 27 functions, still over
 * detekt's `TooManyFunctions` ceiling (25), so these two stay external. Unlike
 * [DestinationLocationResolver], this class needs no injected dependency at all -- it is pure
 * guard logic over a [DeliveryOrder] entity, mirroring
 * [GoodsReceiptService.requireClaimable]/`requireNotAlreadyClaimed`'s split (there, kept as
 * private methods on the service itself, because that service had the function-count headroom;
 * here it does not).
 */
@ApplicationScoped
class OrderClaimGuard {

    /** Split from [requireNotAlreadyClaimed] so neither trips detekt's `ThrowsCount` (max 2). */
    fun requireClaimable(order: DeliveryOrder, id: Long) {
        if (order.state >= OrderState.FINISHED.code) {
            throw OrderException.OrderClaimConflict(id, "order is closed (state ${order.state})")
        }
        requireNotAlreadyClaimed(order, id)
    }

    private fun requireNotAlreadyClaimed(order: DeliveryOrder, id: Long) {
        if (order.operatorId != null) {
            throw OrderException.OrderClaimConflict(id, "already claimed by '${order.operatorId}'")
        }
    }
}

package com.karyo.orders.service

import com.karyo.orders.spi.OrderProgressionPort
import com.karyo.orders.vo.OrderState
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [OrderProgressionPort] impl. Delegates to [OrderService.progressTo], which routes
 * through the same forward-only transition chokepoint as the REST lifecycle.
 *
 * **Idempotency guard (final-review fix wave, CRITICAL 2, outbound-completion sprint).**
 * [OrderService.progressTo] -> `transition` throws `OrderException.InvalidTransition` whenever
 * `target == current` (`OrderState.canAdvanceTo` returns false for that case) -- correct for the
 * REST-driven, strictly-forward lifecycle, but wrong for [markPacked]: cancel a PACKED shipment
 * (`ShippingLifecycleService.cancel` restores stock and flips the SHIPMENT to CANCELED, but never
 * touches the DeliveryOrder, which stays at PACKED), reopen packing, and pack again --
 * `PackingService.pack`'s completion then calls `markPacked` while the order is ALREADY at
 * PACKED, and the strict guard rolled back the entire re-pack transaction (the shipment flip
 * too, since both share it). [progressIfBehind] makes the mark a no-op once the order is already
 * at or past the target instead of throwing -- the order's state is exactly what it should be
 * either way, so "no-op" and "transition" reach the same end state.
 *
 * [markShipped]/[markFinished] get the same treatment on symmetry grounds, even though no
 * path replays them today: `ShipmentState.canAdvanceTo(CANCELED)` is gated to `code <
 * SHIPPING`, so a shipment can never be canceled-and-redispatched once it reaches
 * SHIPPING/SHIPPED, and `ShippingService.dispatch` itself is gated to the SHIPPING state
 * exactly (one shot, no re-entry). Making all three consistent removes the trap before it can
 * resurface if that gate ever loosens, at zero behavioral cost today.
 *
 * [markPicked]/[markStarted] are left strict: no reachable flow on this branch replays them, and
 * an unexpected repeat there is more likely a real bug worth surfacing than a benign retry.
 *
 * **[markPacking]/[markShipping] (row 8).** Added for the `sendToPacking`/`sendToShipping`
 * strategy flags -- state PARKING only, entered between PICKED/PACKED and PACKED/SHIPPED
 * respectively. Given [progressIfBehind]'s no-op-at-or-past-target shape, [markPacked] called
 * with the order already parked at PACKING (or [markShipped] with it already parked at SHIPPING)
 * simply continues forward -- the "PACKING still moves it to PACKED" / "SHIPPING still moves it
 * to SHIPPED" behavior the flags promise. Both use [progressIfBehind] rather than a strict call:
 * a flag flip mid-flight (or a retry) must not throw where the order is merely already parked.
 */
@ApplicationScoped
class DefaultOrderProgressionPort(
    private val orderService: OrderService,
) : OrderProgressionPort {

    override fun markPicked(orderId: Long, clientId: Long) {
        orderService.progressTo(orderId, clientId, OrderState.PICKED)
    }

    override fun markStarted(orderId: Long, clientId: Long) {
        orderService.progressTo(orderId, clientId, OrderState.STARTED)
    }

    override fun markPacking(orderId: Long, clientId: Long) {
        progressIfBehind(orderId, clientId, OrderState.PACKING)
    }

    override fun markPacked(orderId: Long, clientId: Long) {
        progressIfBehind(orderId, clientId, OrderState.PACKED)
    }

    override fun markShipping(orderId: Long, clientId: Long) {
        progressIfBehind(orderId, clientId, OrderState.SHIPPING)
    }

    override fun markShipped(orderId: Long, clientId: Long) {
        progressIfBehind(orderId, clientId, OrderState.SHIPPED)
    }

    override fun markFinished(orderId: Long, clientId: Long) {
        progressIfBehind(orderId, clientId, OrderState.FINISHED)
    }

    /** No-op once the order is already at or past [target] instead of throwing -- see class KDoc. */
    private fun progressIfBehind(orderId: Long, clientId: Long, target: OrderState) {
        val current = OrderState.fromCode(orderService.findById(orderId, clientId).state)
        if (current.code >= target.code) return
        orderService.progressTo(orderId, clientId, target)
    }
}

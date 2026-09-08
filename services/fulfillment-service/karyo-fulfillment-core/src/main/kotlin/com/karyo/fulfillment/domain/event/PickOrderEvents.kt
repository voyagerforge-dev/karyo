package com.karyo.fulfillment.domain.event

import java.math.BigDecimal
import java.time.Instant

/** [deliveryOrderId] is null for an EXTINGUISH order (V605, WORKLIST row 20) — no backing DeliveryOrder. */
data class PickOrderCreatedEvent(
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val deliveryOrderId: Long?,
    val clientId: Long,
    val pickCount: Int,
    val occurredAt: Instant,
)

/** [deliveryOrderId] is null for an EXTINGUISH order (V605, WORKLIST row 20) — no backing DeliveryOrder. */
data class PickOrderPickedEvent(
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val deliveryOrderId: Long?,
    val clientId: Long,
    val occurredAt: Instant,
)

/**
 * Row 8 (`createShippingOrder`): fired synchronously by [com.karyo.fulfillment.service.PickOrderService.confirmPick]
 * on pick-order completion, but observed with `@Observes(during = TransactionPhase.AFTER_SUCCESS)`
 * (see [com.karyo.fulfillment.service.PackingService]'s observer) -- NOT called directly in the
 * same transaction. `openPacking`'s own query re-reads the PickOrder via a fresh
 * [Transactional.TxType.REQUIRES_NEW] transaction, which cannot see the PICKED state
 * `confirmPick` just set but has not yet committed; deferring to AFTER_SUCCESS is load-bearing,
 * not decoration -- a same-transaction direct call refuses every time with "pick order is not
 * fully picked" (caught while implementing this exact bug).
 */
data class PickOrderAutoPackEvent(
    val deliveryOrderId: Long,
    val clientId: Long,
)

/**
 * Force-finish cancel outcome (adjudication 2 in the picking-block sprint plan): [canceledPickCount]
 * open (pre-PICKED) picks were canceled + their reservations released; [keptPickedCount] already-PICKED
 * picks were left untouched. The PickOrder itself lands on CANCELED when [keptPickedCount] is zero,
 * else on the same PICKED(600) terminal code normal completion uses (Karyo has no separate FINISHED
 * pick-state code — see PickLifecycleService KDoc).
 */
data class PickOrderCanceledEvent(
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val deliveryOrderId: Long?,
    val clientId: Long,
    val canceledPickCount: Int,
    val keptPickedCount: Int,
    val occurredAt: Instant,
)

/**
 * Row 15 top-up (`PickTopUpService.addPicksToOrder`): [addedCount] new picks were merged into an
 * existing PickOrder without recalculating its own state (myWMS-faithful — see the service KDoc).
 * Reused verbatim by Task 5's extinguish-order merge path.
 */
data class PickOrderPicksAddedEvent(
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val deliveryOrderId: Long?,
    val clientId: Long,
    val addedCount: Int,
    val occurredAt: Instant,
)

data class PickShortfallReportedEvent(
    val pickId: Long,
    val deliveryOrderId: Long,
    val deliveryOrderLineId: Long,
    val itemDataId: Long,
    val shortfall: BigDecimal,
    val resolution: String,
    val clientId: Long,
    val occurredAt: Instant,
)

/** [deliveryOrderId] is null for a GROUP (cross-order, Sprint C) shipment -- members live in shipment_orders. */
data class ShipmentStateChangedEvent(
    val shipmentId: Long,
    val shipmentNumber: String,
    val deliveryOrderId: Long?,
    val oldState: Int,
    val newState: Int,
    val clientId: Long,
    val occurredAt: Instant,
)

/**
 * S4 (outbound-completion sprint): fired once per [ShippingLifecycleService.cancel] call.
 * [deliveryOrderId] is null for a GROUP (cross-order, Sprint C) shipment.
 */
data class ShipmentCanceledEvent(
    val shipmentId: Long,
    val shipmentNumber: String,
    val deliveryOrderId: Long?,
    val clientId: Long,
    val restoredUnitCount: Int,
    val occurredAt: Instant,
)

/**
 * IMPORTANT 6 (final-review fix wave, outbound-completion sprint): fired once per
 * [ShippingLifecycleService.removeUnit] call -- before this, a unit removal was invisible in the
 * outbox, including the PACKED-to-PACKING shipment regression it can trigger. [resultingState]
 * carries the shipment's state AFTER the removal (already regressed to PACKING when applicable).
 */
data class ShippingUnitRemovedEvent(
    val shipmentId: Long,
    val shippingUnitId: Long,
    val shippingUnitNumber: String,
    val resultingState: Int,
    val clientId: Long,
    val occurredAt: Instant,
)

/**
 * A claimed PickOrder was handed back to the unclaimed pool (STARTED -> RELEASED) by
 * [com.karyo.fulfillment.service.PickOrderService.release] -- NOT the `releaseToPicking`
 * generation path, which mints orders already in RELEASED and reports itself through
 * [PickOrderCreatedEvent].
 *
 * The audit reason this exists: a MANAGER may release work claimed by someone else
 * (`WorkProvider.release(asManager = true)`), and before this event that override left no trace
 * at all. [releasedFrom] is the operator who HELD the work, [releasedBy] the one who performed
 * the release; [managerOverride] is derived from those two rather than from the caller's
 * `asManager` capability flag, because that flag only says the actor *could* override -- a
 * manager releasing their own work is a self-release. A null [releasedFrom] (an unheld order
 * force-released by a manager) is not an override either: no one's work was taken.
 *
 * Outbox-only, like every sibling in this file -- no in-process consumer, and the outbox row is
 * the durable audit record.
 */
data class PickOrderReleasedEvent(
    val pickOrderId: Long,
    val pickOrderNumber: String,
    val deliveryOrderId: Long?,
    val releasedFrom: String?,
    val releasedBy: String,
    val managerOverride: Boolean,
    val clientId: Long,
    val occurredAt: Instant,
)

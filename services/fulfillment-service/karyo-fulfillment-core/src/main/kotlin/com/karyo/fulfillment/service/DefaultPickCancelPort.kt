package com.karyo.fulfillment.service

import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.spi.PickCancelPort
import com.karyo.fulfillment.vo.PickState
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/**
 * Default in-process [PickCancelPort]: force-finishes the delivery order's still-open pick work
 * through the same [PickLifecycleService.forceFinish] body the REST pick-order cancel uses, minus
 * the operator-claim guards (see the SPI KDoc for why the cascade supersedes them).
 *
 * A pick order already at/past PICKED is skipped rather than refused: the delivery-order cancel is
 * legal up to PENDING(550), so a PickOrder that completed on its own is simply nothing left to
 * cancel. Joins the caller's transaction, so the picks it cancels are flushed and visible to the
 * caller's own terminal-slice query in the same transaction.
 *
 * **Sibling-aware (Task 8 review fix, register row 8, Critical).** [PickOrderRepository.
 * findAllByDeliveryOrderId] returns EVERY pick order for [deliveryOrderId] -- `createTypeOrders`
 * can split a single release into more than one, so a delivery-order cancel must reconcile every
 * sibling's open work. The old comment here claimed `findByDeliveryOrderId` "returns at most one
 * order (Karyo mints exactly one pick order per delivery order)"; that was true before
 * `createTypeOrders` existed and is false now -- leaving it in place after that changed would have
 * left a sibling's live reservation untouched on cancel, contradicting the cancel-restock
 * guarantee an earlier sprint built and tested.
 */
@ApplicationScoped
class DefaultPickCancelPort(
    private val pickOrderRepository: PickOrderRepository,
    private val pickLifecycleService: PickLifecycleService,
) : PickCancelPort {

    @Transactional
    override fun cancelOpenWorkForDeliveryOrder(deliveryOrderId: Long, clientId: Long): Int =
        pickOrderRepository.findAllByDeliveryOrderId(deliveryOrderId, clientId)
            .filter { it.state < PickState.PICKED.code }
            .onEach { pickLifecycleService.forceFinish(it) }
            .count()
}

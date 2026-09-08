package com.karyo.orders.service

import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.spi.OrderStrategyContext
import com.karyo.orders.spi.OrderStrategyLookup
import com.karyo.orders.spi.PickingStrategyView
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [OrderStrategyLookup] impl. Resolves the order's [OrderStrategy] through the configured
 * resolver chain ([OrderStrategyService.resolve]) and projects the picking-relevant knobs for
 * the fulfillment module. Tenant-scoped with no system-client bypass (mirrors
 * [DefaultDeliveryOrderLookup]): an unset/mismatched tenant returns null (fails closed).
 */
@ApplicationScoped
class DefaultOrderStrategyLookup(
    private val orderRepository: DeliveryOrderRepository,
    private val orderStrategyService: OrderStrategyService,
    private val tenantContext: TenantContext,
) : OrderStrategyLookup {

    override fun findPickingStrategy(orderId: Long): PickingStrategyView? =
        findPickingStrategy(orderId, tenantContext.clientId)

    override fun findPickingStrategy(orderId: Long, clientId: Long): PickingStrategyView? {
        val order = orderRepository.findByIdAndClient(orderId, clientId) ?: return null
        val strategy = orderStrategyService.resolve(OrderStrategyContext(order.orderStrategyId, order.clientId))
        return PickingStrategyView(
            shortPickMode = strategy.shortPickMode,
            shortfallStrategy = strategy.shortfallStrategy,
            pickDifferenceStrategy = strategy.pickDifferenceStrategy,
            packoutStrategy = strategy.packoutStrategy,
            useLockedStock = strategy.useLockedStock,
            preferComplete = strategy.preferComplete,
            preferMatching = strategy.preferMatching,
            completeHandling = strategy.completeHandling,
            enforceLot = strategy.enforceLot,
            sendToPacking = strategy.sendToPacking,
            sendToShipping = strategy.sendToShipping,
            createShippingOrder = strategy.createShippingOrder,
            createTypeOrders = strategy.createTypeOrders,
            defaultDestinationLocationId = strategy.defaultDestinationLocationId,
        )
    }
}

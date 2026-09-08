package com.karyo.orders.service

import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.sequence.SequenceNumberService
import jakarta.enterprise.context.ApplicationScoped

/**
 * Wave bulk-fulfillment sprint: split out of [OrderService] into its own tiny class (mirroring
 * [OrderClaimGuard]/[DestinationLocationResolver]) because the sprint's new
 * [OrderService.releaseLineReservations] pushed the class past detekt's `TooManyFunctions`
 * ceiling (25). Resolves the `delivery_orders.order_number` for [OrderService.create]: the
 * caller-supplied number if given (validated non-blank and unique), else a generated one.
 */
@ApplicationScoped
class OrderNumberResolver(
    private val orderRepository: DeliveryOrderRepository,
    private val sequenceNumberService: SequenceNumberService,
) {

    fun resolve(requested: String?, clientId: Long): String =
        if (requested != null) validateRequested(requested, clientId) else generate(clientId)

    private fun validateRequested(requested: String, clientId: Long): String {
        if (requested.isBlank()) {
            throw OrderException.ValidationFailed("orderNumber must not be blank")
        }
        orderRepository.findByOrderNumber(requested, clientId)?.let {
            throw OrderException.DuplicateName("DeliveryOrder", requested)
        }
        return requested
    }

    /** delivery_orders.order_number is VARCHAR(100). */
    private fun generate(clientId: Long): String =
        sequenceNumberService.next("order.orderNumber", "DO", clientId, MAX_NUMBER_LENGTH) { candidate ->
            orderRepository.findByOrderNumber(candidate, clientId) == null
        }

    private companion object {
        private const val MAX_NUMBER_LENGTH = 100
    }
}

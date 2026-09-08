package com.karyo.orders.service

import com.karyo.orders.repository.DeliveryOrderLineRepository
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.spi.DeliveryOrderLookup
import com.karyo.orders.spi.LineForPicking
import com.karyo.orders.spi.OrderForPicking
import com.karyo.orders.spi.ReservationSlice
import com.karyo.orders.spi.ShipToView
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [DeliveryOrderLookup] impl. Reads the order via the tenant-scoped repository and
 * groups its [OrderLineReservation] slices by line for the fulfillment module's pick generation.
 *
 * Deliberately tenant-scoped with **no system-client bypass** (unlike `DefaultProductLookup`):
 * pick generation always runs in a single owner's context, so a cross-tenant/admin read is never
 * wanted here. An unset/mismatched `tenantContext.clientId` returns null (fails closed, no leak).
 */
@ApplicationScoped
class DefaultDeliveryOrderLookup(
    private val orderRepository: DeliveryOrderRepository,
    private val reservationRepository: OrderLineReservationRepository,
    private val lineRepository: DeliveryOrderLineRepository,
    private val tenantContext: TenantContext,
) : DeliveryOrderLookup {

    override fun findShipTo(orderId: Long): ShipToView? {
        val order = orderRepository.findByIdAndClient(orderId, tenantContext.clientId) ?: return null
        return ShipToView(
            customerName = order.customerName,
            street = order.street,
            streetNumber = order.streetNumber,
            zipCode = order.zipCode,
            city = order.city,
            country = order.country,
            phone = order.phone,
            email = order.email,
        )
    }

    override fun isCanceled(orderId: Long): Boolean =
        orderRepository.findByIdAndClient(orderId, tenantContext.clientId)?.state == OrderState.CANCELED.code

    override fun orderIdForLine(lineId: Long, clientId: Long): Long? =
        lineRepository.findByIdAndClient(lineId, clientId)?.deliveryOrder?.id

    override fun findForPicking(orderId: Long): OrderForPicking? =
        findForPicking(orderId, tenantContext.clientId)

    override fun findForPicking(orderId: Long, clientId: Long): OrderForPicking? {
        val order = orderRepository.findByIdAndClient(orderId, clientId) ?: return null
        val lineIds = order.lines.mapNotNull { it.id }
        val byLine = reservationRepository.findByLineIds(lineIds).groupBy { it.lineId }
        return OrderForPicking(
            orderId = order.id!!,
            orderNumber = order.orderNumber,
            state = order.state,
            clientId = order.clientId,
            destinationLocationId = order.destinationLocationId,
            lines = order.lines.map { line ->
                LineForPicking(
                    lineId = line.id!!,
                    lineNumber = line.lineNumber,
                    itemDataId = line.itemDataId,
                    itemDataNumber = line.itemDataNumber,
                    lotNumber = line.lotNumber,
                    reservations = (byLine[line.id] ?: emptyList()).map { ReservationSlice(it.stockUnitId, it.amount) },
                )
            },
        )
    }
}

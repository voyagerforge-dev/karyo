package com.karyo.orders.service

import com.karyo.inventory.api.spi.StockReserver
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.domain.model.OrderLineReservation
import com.karyo.orders.repository.AsnRepository
import com.karyo.orders.repository.DeliveryOrderLineRepository
import com.karyo.orders.repository.GoodsReceiptLineRepository
import com.karyo.orders.repository.OrderLineReservationRepository
import com.karyo.orders.spi.CrossDockCandidateLine
import com.karyo.orders.spi.CrossDockOrdersPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * Default [CrossDockOrdersPort] implementation. Mirrors [OrderService.reserveLine]'s
 * bookkeeping exactly for the slice-level reserve/release path: an [OrderLineReservation] row
 * per slice plus the line's `reservedAmount` running total, and the same [StockReserver]
 * collaborator for the stock-side `reservedAmount`.
 *
 * Uses [StockReserver]'s explicit-`clientId` overloads (`reserveOnStockUnit`/`release` taking
 * `clientId` as a parameter), NOT the ambient-`TenantContext` ones -- this port is a synchronous
 * cross-module seam that a caller (e.g. a `@Scheduled` expiry sweep) may invoke outside a primed
 * request scope, where the ambient `TenantContext.clientId` defaults to 0. The explicit overloads
 * are strictly scoped to the [clientId] this port was actually called with and throw
 * [com.karyo.inventory.exception.InventoryException.Forbidden] on a genuine tenant mismatch
 * (never silently swallowed as "unit gone") -- see the review that added them for the
 * availability-leak this closes: the ambient overloads would have thrown `NotFound` on the
 * scope check, which `DefaultStockReserver.release`'s ambient path treats as a defensive
 * "already deleted" no-op, letting `releaseSlice` delete its own row and decrement
 * `line.reservedAmount` while the stock unit's `reservedAmount` was never touched.
 */
@ApplicationScoped
class DefaultCrossDockOrdersPort(
    private val goodsReceiptLineRepository: GoodsReceiptLineRepository,
    private val asnRepository: AsnRepository,
    private val lineRepository: DeliveryOrderLineRepository,
    private val reservationRepository: OrderLineReservationRepository,
    private val stockReserver: StockReserver,
) : CrossDockOrdersPort {

    override fun crossDockTargetFor(goodsReceiptLineId: Long, clientId: Long): Long? {
        val grLine = goodsReceiptLineRepository.findByIdAndClient(goodsReceiptLineId, clientId) ?: return null
        val asnLineId = grLine.asnLineId ?: return null
        return asnRepository.findLineById(asnLineId, clientId)?.crossDockDeliveryOrderId
    }

    override fun openLineCandidates(itemDataId: Long, clientId: Long): List<CrossDockCandidateLine> =
        lineRepository.findOpenByItem(itemDataId, clientId).map(::toCandidate)

    override fun openLinesOf(deliveryOrderId: Long, itemDataId: Long, clientId: Long): List<CrossDockCandidateLine> =
        lineRepository.findOpenByOrderAndItem(deliveryOrderId, itemDataId, clientId).map(::toCandidate)

    @Transactional
    override fun reserveSlice(
        deliveryOrderLineId: Long,
        stockUnitId: Long,
        amount: BigDecimal,
        clientId: Long,
    ): Boolean {
        val line = lineRepository.findByIdAndClient(deliveryOrderLineId, clientId) ?: return false
        if (line.shortage.signum() <= 0) return false
        // Never let a slice push reservedAmount past amount -- the line's own shortage ceiling.
        if (amount > line.shortage) return false
        val correlationId = "cross-dock:${line.deliveryOrder.orderNumber}"
        if (!stockReserver.reserveOnStockUnit(stockUnitId, amount, clientId, correlationId)) return false

        reservationRepository.persist(
            OrderLineReservation().apply {
                lineId = deliveryOrderLineId
                this.stockUnitId = stockUnitId
                this.amount = amount
            }
        )
        line.reservedAmount = line.reservedAmount.add(amount)
        return true
    }

    @Transactional
    override fun releaseSlice(deliveryOrderLineId: Long, stockUnitId: Long, clientId: Long) {
        val line = lineRepository.findByIdAndClient(deliveryOrderLineId, clientId) ?: return
        val slices = reservationRepository.findByLineAndStockUnit(deliveryOrderLineId, stockUnitId, clientId)
        if (slices.isEmpty()) return

        val released = slices.sumOf { it.amount }
        val correlationId = "cross-dock:${line.deliveryOrder.orderNumber}"
        // Stock-side FIRST: a genuine tenant-mismatch throws (InventoryException.Forbidden) and
        // rolls back this whole @Transactional call, so the orders-side row/reservedAmount below
        // is only ever mutated once the stock-side release actually succeeded (or the unit was
        // genuinely gone, the one case release() still treats as a defensive no-op).
        stockReserver.release(stockUnitId, released, clientId, correlationId)

        reservationRepository.delete(
            "lineId = ?1 and stockUnitId = ?2",
            deliveryOrderLineId,
            stockUnitId,
        )
        line.reservedAmount = line.reservedAmount.subtract(released).max(BigDecimal.ZERO)
    }

    override fun sliceExists(deliveryOrderLineId: Long, stockUnitId: Long, clientId: Long): Boolean =
        reservationRepository.findByLineAndStockUnit(deliveryOrderLineId, stockUnitId, clientId).isNotEmpty()

    private fun toCandidate(line: DeliveryOrderLine): CrossDockCandidateLine =
        CrossDockCandidateLine(
            deliveryOrderLineId = line.id!!,
            deliveryOrderId = line.deliveryOrder.id!!,
            openAmount = line.shortage,
            shipBy = line.deliveryOrder.deliveryDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant(),
        )
}

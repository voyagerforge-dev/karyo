package com.karyo.orders.service

import com.karyo.documents.DocumentRenderer
import com.karyo.documents.DocumentStore
import com.karyo.fulfillment.spi.PickRollupLookup
import com.karyo.fulfillment.vo.PickRollup
import com.karyo.orders.domain.model.DeliveryOrder
import com.karyo.orders.domain.model.DeliveryOrderLine
import com.karyo.orders.exception.OrderException
import com.karyo.orders.repository.DeliveryOrderRepository
import com.karyo.orders.vo.OrderState
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Renders the delivery note PDF (D7) — the document that accompanies goods on dispatch,
 * reconciling what was ordered against what was actually picked (incl. substitutions) per
 * line. Gated on [OrderState.PICKED] or later: before picking there is nothing yet to
 * reconcile, so the document is refused with [OrderException.DocumentNotReady] (409) —
 * mirrors [com.karyo.fulfillment.service.ShipmentDocumentService]'s gated documents.
 *
 * Read-only: doesn't mutate, so not `@Transactional` (mirrors [PickDocumentService]/
 * [ShipmentDocumentService]).
 */
@ApplicationScoped
class OrderDocumentService(
    private val orderRepository: DeliveryOrderRepository,
    private val pickRollupLookup: PickRollupLookup,
    private val renderer: DocumentRenderer,
    private val tenantContext: TenantContext,
    private val documentStore: Instance<DocumentStore>,
) {

    fun deliveryNotePdf(orderId: Long, store: Boolean = false): ByteArray {
        val order = gatedOrder(orderId)
        val bytes = renderer.htmlToPdf(renderer.render("/templates/delivery-note.html", noteData(order), order.clientId))
        if (store && documentStore.isResolvable) {
            documentStore.get().store(
                ownerClientId = order.clientId,
                entityType = "delivery-order",
                entityId = order.id!!,
                documentType = "delivery-note",
                fileName = "delivery-note-${order.id}.pdf",
                mediaType = "application/pdf",
                content = bytes,
            )
        }
        return bytes
    }

    private fun gatedOrder(orderId: Long): DeliveryOrder {
        val order = orderRepository.findByIdAndClient(orderId, tenantContext.clientId)
            ?: throw OrderException.NotFound("DeliveryOrder", "id=$orderId")
        if (order.state < OrderState.PICKED.code) {
            throw OrderException.DocumentNotReady(
                orderId,
                "delivery-note",
                "order state ${order.state} is below PICKED(${OrderState.PICKED.code})",
            )
        }
        return order
    }

    /**
     * Builds the ordered-vs-picked reconciliation data. The totals row sums PRICED lines
     * only (an unpriced line contributes nothing, never a fabricated price); [hasUnpriced]
     * drives the "unpriced lines excluded from total" footnote whenever ANY line lacks a
     * price, even if the total itself is non-zero from other priced lines.
     *
     * The substituted column is always rendered (never conditionally hidden) — it shows
     * "—" when zero, consistent with every other missing-value convention in this
     * template, rather than adding a second whole-document layout for the rarer
     * substitution case.
     */
    private fun noteData(order: DeliveryOrder): Map<String, Any?> {
        val lineIds = order.lines.mapNotNull { it.id }.toSet()
        val rollups = pickRollupLookup.pickedAmountsByLineIds(lineIds)
        val hasUnpriced = order.lines.any { it.unitPrice == null }
        val total = order.lines
            .mapNotNull { line -> line.unitPrice?.multiply(line.amount) }
            .fold(BigDecimal.ZERO, BigDecimal::add)
            .setScale(TOTAL_SCALE, RoundingMode.HALF_UP)
        return mapOf(
            "orderNumber" to order.orderNumber,
            // Row 10: Karyo-native sender (the outbound counterpart of Asn.senderName); a
            // column no document prints has no purpose, so surface it here, falling back to
            // today's output (no sender line at all) when null.
            "hasSender" to (order.senderName != null),
            "senderName" to (order.senderName ?: ""),
            "customerName" to (order.customerName ?: "—"),
            "street" to (order.street ?: ""),
            "streetNumber" to (order.streetNumber ?: ""),
            "zipCode" to (order.zipCode ?: ""),
            "city" to (order.city ?: ""),
            "country" to (order.country ?: ""),
            "deliveryDate" to (order.deliveryDate?.toString() ?: "—"),
            "generatedAt" to Instant.now().toString(),
            "lines" to order.lines.map { line -> lineMap(line, rollups[line.id]) },
            "total" to total.toPlainString(),
            "hasUnpriced" to hasUnpriced,
            "hasNotes" to (order.notes != null),
            "notes" to (order.notes ?: ""),
        )
    }

    private fun lineMap(line: DeliveryOrderLine, rollup: PickRollup?): Map<String, Any?> {
        val picked = rollup?.pickedAmount ?: BigDecimal.ZERO
        val substituted = rollup?.substitutedAmount ?: BigDecimal.ZERO
        return mapOf(
            "itemDataNumber" to line.itemDataNumber,
            "externalNumber" to (line.externalNumber ?: "—"),
            "lotNumber" to (line.lotNumber ?: "—"),
            "ordered" to line.amount.toPlainString(),
            "picked" to picked.toPlainString(),
            "substituted" to if (substituted.signum() > 0) substituted.toPlainString() else "—",
            "unitPrice" to (line.unitPrice?.toPlainString() ?: "—"),
            "lineTotal" to (
                line.unitPrice?.multiply(line.amount)?.setScale(TOTAL_SCALE, RoundingMode.HALF_UP)?.toPlainString()
                    ?: "—"
                ),
        )
    }

    companion object {
        private const val TOTAL_SCALE = 2
    }
}

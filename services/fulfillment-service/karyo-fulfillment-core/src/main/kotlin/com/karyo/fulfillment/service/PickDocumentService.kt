package com.karyo.fulfillment.service

import com.karyo.documents.DocumentRenderer
import com.karyo.documents.DocumentStore
import com.karyo.fulfillment.domain.model.Pick
import com.karyo.fulfillment.domain.model.PickOrder
import com.karyo.fulfillment.exception.FulfillmentException
import com.karyo.fulfillment.repository.PickOrderRepository
import com.karyo.fulfillment.repository.PickRepository
import com.karyo.fulfillment.vo.PickState
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.security.TenantContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.time.Instant

/**
 * Renders the pick ticket PDF for a pick order — the floor-facing picklist an operator carries:
 * header (pick order #, delivery order #, prio, operator, generated timestamp) + one row per
 * pick (sequence, SKU, lot, planned qty, source location, source unit load, a blank confirm box).
 *
 * Deliberately NOT gated by [DocumentAvailabilityResolver] the way the shipment documents are:
 * a pick ticket is useful in *every* pre-terminal pick-order state — CREATED (preview before
 * release), RELEASED/STARTED (the working copy an operator prints and carries), even PICKED
 * (a record of what was picked). There is no "not ready yet" for a picklist the way there is
 * for a BOL that needs a carrier assigned first.
 *
 * Read-only: doesn't mutate, so not `@Transactional` (mirrors [ShipmentDocumentService]).
 */
@ApplicationScoped
class PickDocumentService(
    private val pickOrderRepository: PickOrderRepository,
    private val pickRepository: PickRepository,
    private val stockUnitLookup: StockUnitLookup,
    private val renderer: DocumentRenderer,
    private val tenantContext: TenantContext,
    private val documentStore: Instance<DocumentStore>,
) {
    fun pickTicketPdf(pickOrderId: Long, store: Boolean = false): ByteArray {
        val po = pickOrderRepository.findByIdAndClient(pickOrderId, tenantContext.clientId)
            ?: throw FulfillmentException.NotFound("PickOrder", pickOrderId)
        val picks = pickRepository.findByPickOrderId(po.id!!)
        val bytes = renderer.htmlToPdf(renderer.render("/templates/pick-ticket.html", ticketData(po, picks), po.clientId))
        if (store && documentStore.isResolvable) {
            documentStore.get().store(
                ownerClientId = po.clientId,
                entityType = "pick-order",
                entityId = po.id!!,
                documentType = "pick-ticket",
                fileName = "pick-ticket-${po.id}.pdf",
                mediaType = "application/pdf",
                content = bytes,
            )
        }
        return bytes
    }

    /**
     * Batches the source location per pick via [StockUnitLookup.findLocationRefsByIds]. A pick
     * whose source stock unit was since deleted (or, defensively, belongs to another tenant)
     * is simply absent from the lookup result — rendered as "—", never a 500.
     */
    private fun ticketData(po: PickOrder, picks: List<Pick>): Map<String, Any?> {
        val locations = stockUnitLookup.findLocationRefsByIds(picks.map { it.sourceStockUnitId }.toSet())
        return mapOf(
            "pickOrder" to mapOf(
                "pickOrderNumber" to po.pickOrderNumber,
                // Row 20: an EXTINGUISH order has no backing DeliveryOrder -- render "—" rather
                // than a fabricated reference (same honest-gap convention as sourceLocation below).
                "deliveryOrderNumber" to (po.deliveryOrderNumber ?: "—"),
                "prio" to po.prio.toString(),
                "operatorId" to (po.operatorId ?: "—"),
                "state" to PickState.fromCode(po.state).name,
            ),
            "picks" to picks.mapIndexed { index, pick ->
                val ref = locations[pick.sourceStockUnitId]
                mapOf(
                    "seq" to (index + 1).toString(),
                    "itemDataNumber" to pick.itemDataNumber,
                    "lotNumber" to pick.lotNumber,
                    "plannedAmount" to pick.plannedAmount.toPlainString(),
                    "sourceLocation" to (ref?.locationName ?: "—"),
                    "sourceUnitLoad" to (ref?.unitLoadLabel ?: "—"),
                )
            },
            "generatedAt" to Instant.now().toString(),
        )
    }
}

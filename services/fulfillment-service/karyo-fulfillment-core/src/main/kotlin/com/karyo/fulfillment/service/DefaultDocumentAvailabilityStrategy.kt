package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.DocumentAvailabilityStrategy
import com.karyo.fulfillment.spi.DocumentType
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in gates (priority MAX, runs last): packing slip and packet list (D9 — shares the slip's
 * gate, it's a plain per-unit listing of an already-packed shipment) available at PACKED(650);
 * BOL + label at SHIPPING(670) (they need carrier/tracking from manifest). A deployment overrides
 * a type via a lower-priority bean.
 */
@ApplicationScoped
class DefaultDocumentAvailabilityStrategy : DocumentAvailabilityStrategy {
    override val priority: Int = Int.MAX_VALUE
    override fun availableFrom(type: DocumentType): Int = when (type) {
        DocumentType.PACKING_SLIP -> 650
        DocumentType.PACKET_LIST -> 650
        DocumentType.BOL -> 670
        DocumentType.SHIPPING_LABEL -> 670
    }
}

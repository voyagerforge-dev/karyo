package com.karyo.fulfillment.spi

enum class DocumentType { BOL, PACKING_SLIP, SHIPPING_LABEL, PACKET_LIST }

/**
 * Strategy-SPI (ADR-036): the minimum Shipment state at which a document type becomes available.
 * Built-in defaults (slip @PACKED 650, BOL/label @SHIPPING 670) are overridable per type by a
 * registered bean (first-non-null by priority). [availableFrom] returns a shipment-state code, or
 * null to defer to the next strategy.
 */
interface DocumentAvailabilityStrategy {
    val priority: Int
    fun availableFrom(type: DocumentType): Int?
}

package com.karyo.fulfillment.spi

import java.math.BigDecimal

/**
 * Strategy-SPI (ADR-036): assigns a carrier + tracking number to a shipment at manifest. v1.3 ships
 * only ManualCarrierAdapter (operator-supplied or generated tracking); real carrier adapters
 * (FedEx/UPS/DHL) register as beans, claim a carrier via [handles], and run at lower priority so they
 * win for their carrier while Manual stays the catch-all. [CarrierAssignment] will grow labelData (3.4b).
 */
interface CarrierAdapter {
    val priority: Int
    fun handles(carrierName: String): Boolean
    fun manifest(request: ManifestRequest): CarrierAssignment
}

data class ManifestRequest(
    val shipmentId: Long,
    val shipmentNumber: String,
    val carrierName: String,
    val carrierService: String,
    val requestedTracking: String?,
    val weight: BigDecimal,
    val clientId: Long,
)

data class CarrierAssignment(
    val carrierName: String,
    val carrierService: String,
    val trackingNumber: String,
)

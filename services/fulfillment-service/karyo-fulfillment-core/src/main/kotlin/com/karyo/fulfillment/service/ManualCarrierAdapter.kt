package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.CarrierAdapter
import com.karyo.fulfillment.spi.CarrierAssignment
import com.karyo.fulfillment.spi.ManifestRequest
import jakarta.enterprise.context.ApplicationScoped

/**
 * Built-in carrier: the catch-all (handles any carrier) at lowest priority. Uses the operator-supplied
 * tracking number, or generates "MAN-{shipmentNumber}" when none is given. Future real-carrier adapters
 * (FedExAdapter etc.) register at lower priority and claim their carrier via handles().
 */
@ApplicationScoped
class ManualCarrierAdapter : CarrierAdapter {
    override val priority: Int = Int.MAX_VALUE
    override fun handles(carrierName: String): Boolean = true

    override fun manifest(request: ManifestRequest): CarrierAssignment = CarrierAssignment(
        carrierName = request.carrierName,
        carrierService = request.carrierService,
        trackingNumber = request.requestedTracking?.takeIf { it.isNotBlank() }
            ?: "MAN-${request.shipmentNumber}",
    )
}

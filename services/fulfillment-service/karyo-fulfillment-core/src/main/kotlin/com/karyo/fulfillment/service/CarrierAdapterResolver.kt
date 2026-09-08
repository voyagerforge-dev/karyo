package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.CarrierAdapter
import com.karyo.fulfillment.spi.CarrierAssignment
import com.karyo.fulfillment.spi.ManifestRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance

/**
 * Resolves the active [CarrierAdapter]: walks ascending priority and takes the first adapter that
 * [CarrierAdapter.handles] the request's carrier (built-in ManualCarrierAdapter, priority MAX_VALUE,
 * is the catch-all and always answers). v1.3 has only the built-in; future real-carrier adapters
 * register as beans at lower priority and claim their carrier.
 */
@ApplicationScoped
class CarrierAdapterResolver(private val adapters: Instance<CarrierAdapter>) {
    fun resolve(request: ManifestRequest): CarrierAssignment {
        val adapter = adapters.sortedBy { it.priority }.firstOrNull { it.handles(request.carrierName) }
            ?: error("No CarrierAdapter handled '${request.carrierName}' (built-in Manual missing?)")
        return adapter.manifest(request)
    }
}

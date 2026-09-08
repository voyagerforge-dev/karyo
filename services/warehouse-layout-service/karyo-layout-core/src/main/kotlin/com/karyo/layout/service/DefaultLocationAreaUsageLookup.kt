package com.karyo.layout.service

import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.LocationAreaUsageLookup
import jakarta.enterprise.context.ApplicationScoped

/** Default [LocationAreaUsageLookup]: delegates straight to [StorageLocationRepository]. */
@ApplicationScoped
class DefaultLocationAreaUsageLookup(
    private val locationRepository: StorageLocationRepository,
) : LocationAreaUsageLookup {

    override fun pickingLocationIds(clientId: Long): Set<Long> =
        locationRepository.findPickingLocationIds(clientId).toSet()

    override fun crossDockStagingLocationIds(clientId: Long): Set<Long> =
        locationRepository.findCrossDockStagingLocationIds(clientId).toSet()
}

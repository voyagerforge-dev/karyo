package com.karyo.layout.service

import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.LocationRef
import com.karyo.layout.spi.StorageLocationLookup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [StorageLocationLookup]: delegates straight to [StorageLocationRepository], tenant-
 * scoped by strict `clientId` equality throughout.
 */
@ApplicationScoped
class DefaultStorageLocationLookup(
    private val locationRepository: StorageLocationRepository,
) : StorageLocationLookup {

    override fun findById(locationId: Long, clientId: Long): LocationRef? =
        locationRepository.findByIdAndClientId(locationId, clientId)?.let { LocationRef(it.id!!, it.name) }

    override fun findNamesByIds(ids: Set<Long>, clientId: Long): Map<Long, String> =
        locationRepository.findNamesByIds(ids, clientId)

    override fun zoneNamesByLocationIds(locationIds: Set<Long>, clientId: Long): Map<Long, String?> =
        locationRepository.findZoneNamesByIds(locationIds, clientId)
}

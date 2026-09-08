package com.karyo.layout.service

import com.karyo.layout.repository.AreaRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.StagingLocation
import com.karyo.layout.spi.StagingLocationLookup
import com.karyo.layout.vo.AreaUsage
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [StagingLocationLookup]: finds an [com.karyo.layout.domain.model.Area] whose comma-joined
 * `usages` contains PACK_STAGING, then the first storage location in those areas for the tenant.
 * Returns null when no PACK_STAGING area/location is configured (seeded by sample-data, not a migration).
 */
@ApplicationScoped
class DefaultStagingLocationLookup(
    private val areaRepository: AreaRepository,
    private val locationRepository: StorageLocationRepository,
) : StagingLocationLookup {

    override fun findPackStaging(clientId: Long): StagingLocation? {
        val stagingAreaIds = areaRepository.listAll()
            .filter { it.usages?.split(",")?.contains(AreaUsage.PACK_STAGING.name) == true }
            .mapNotNull { it.id }
        if (stagingAreaIds.isEmpty()) return null
        val location = locationRepository.findFirstByAreaIdsAndClient(stagingAreaIds, clientId) ?: return null
        return StagingLocation(location.id!!, location.name)
    }

    override fun findShipStaging(clientId: Long): StagingLocation? {
        val stagingAreaIds = areaRepository.listAll()
            .filter { it.usages?.split(",")?.contains(AreaUsage.SHIP_STAGING.name) == true }
            .mapNotNull { it.id }
        if (stagingAreaIds.isEmpty()) return null
        val location = locationRepository.findFirstByAreaIdsAndClient(stagingAreaIds, clientId) ?: return null
        return StagingLocation(location.id!!, location.name)
    }
}

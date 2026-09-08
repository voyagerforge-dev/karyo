package com.karyo.layout.service

import com.karyo.layout.dto.LockLocationRequest
import com.karyo.layout.repository.LocationReservationRepository
import com.karyo.layout.repository.StorageAreaRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.layout.spi.LocationLockPort
import com.karyo.layout.vo.LockType
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

/**
 * Default [LocationLockPort]: delegates lock/unlock to [LocationService] (which owns the
 * outbox event + cache invalidation), and uses [StorageLocationRepository] directly for
 * read-only expansion and name lookup (no state change, no cache concern).
 */
@ApplicationScoped
class DefaultLocationLockPort(
    private val locationService: LocationService,
    private val locationRepository: StorageLocationRepository,
    private val storageAreaRepository: StorageAreaRepository,
    private val reservationRepository: LocationReservationRepository,
) : LocationLockPort {

    /** Effective-allocation floor above which a location counts as full/occupied -- matches
     *  [LocationFinderService]'s own `ALLOCATION_FULL` reading of the myWMS `allocation >= 100` rule. */
    private val allocationFull = BigDecimal(100)

    override fun lockForCount(locationId: Long, clientId: Long) {
        locationService.lockLocation(locationId, LockLocationRequest(LockType.STOCKTAKING.code), clientId)
    }

    override fun releaseCount(locationId: Long, clientId: Long) {
        locationService.lockLocation(locationId, LockLocationRequest(LockType.UNLOCKED.code), clientId)
    }

    override fun expandAreaToLocations(areaId: Long, clientId: Long): List<Long> =
        locationRepository.findByArea(areaId, clientId).mapNotNull { it.id }

    override fun findIdsByNamePattern(pattern: String, clientId: Long): List<Long> =
        locationRepository.findIdsByNamePattern(pattern, clientId)

    override fun locationName(locationId: Long, clientId: Long): String? =
        locationRepository.findById(locationId)
            ?.takeIf { it.clientId == clientId }
            ?.name

    override fun allStorageLocationIds(clientId: Long): List<Long> =
        locationRepository.findAllIdsOrdered(clientId)

    override fun lockedLocationIds(locationIds: List<Long>, clientId: Long): Set<Long> =
        locationRepository.findLockedIds(locationIds, clientId).toSet()

    /**
     * Row 4 (defect-burndown-4, Task 5): base allocation + live reservation load, same
     * "effective allocation" split [LocationFinderService.effectiveAllocation] uses -- one
     * batched base-allocation query, one batched reservation-load query, folded together
     * in-memory rather than a single join, mirroring that class's own "SQL base, then
     * in-service for reservations" doctrine.
     */
    override fun occupiedLocationIds(locationIds: List<Long>, clientId: Long): Set<Long> {
        if (locationIds.isEmpty()) return emptySet()
        val baseAllocation = locationRepository.findAllocationByIds(locationIds, clientId)
        val reservationLoad = reservationRepository.activeLoadByLocation(locationIds, Instant.now())
        return locationIds.filterTo(mutableSetOf()) { id ->
            val effective = (baseAllocation[id] ?: BigDecimal.ZERO) + (reservationLoad[id] ?: BigDecimal.ZERO)
            effective >= allocationFull
        }
    }

    override fun markCounted(locationIds: List<Long>, at: Instant) {
        locationService.markCounted(locationIds, at)
    }

    /**
     * Walking-order index per location. Rows whose id or `order_index` does not come back as a
     * non-null number are DROPPED rather than cast blindly: a hard `as Int` on a nullable
     * `order_index` would throw inside the work inbox (a bare 500 on every dispatch call), and a
     * missing entry here is already handled by the caller as "no known walking position".
     */
    override fun orderIndexFor(locationIds: List<Long>): Map<Long, Int> =
        locationRepository.findOrderIndexByIds(locationIds)
            .mapNotNull { row ->
                val id = row[0] as? Number ?: return@mapNotNull null
                val index = row[1] as? Number ?: return@mapNotNull null
                id.toLong() to index.toInt()
            }
            .toMap()

    override fun isTransferStaging(locationId: Long): Boolean =
        storageAreaRepository.isTransferStaging(locationId)

    /**
     * Row 16 (defect-burndown-4, Task 11): reuses [LocationReservationRepository.activeLoadByLocation]
     * -- already batched and already filters to non-expired rows -- and takes just the key set,
     * since a countability check only needs "reserved or not", never the reserved percentage.
     */
    override fun reservedLocationIds(locationIds: List<Long>): Set<Long> {
        if (locationIds.isEmpty()) return emptySet()
        return reservationRepository.activeLoadByLocation(locationIds, Instant.now()).keys
    }
}

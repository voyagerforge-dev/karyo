package com.karyo.layout.service

import com.karyo.common.pagination.PaginatedResponse
import com.karyo.common.pagination.PaginationParams
import com.karyo.common.pagination.SortParser
import com.karyo.common.pagination.paginatedResponse
import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.dto.*
import com.karyo.layout.event.LocationAllocationChangedEvent
import com.karyo.layout.event.LocationLockChangedEvent
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.*
import com.karyo.layout.vo.LockType
import io.quarkus.cache.CacheInvalidate
import io.quarkus.cache.CacheKey
import io.quarkus.cache.CacheResult
import io.quarkus.panache.common.Page
import io.quarkus.panache.common.Sort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.PersistenceException
import jakarta.transaction.Transactional
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
class LocationService(
    private val locationRepository: StorageLocationRepository,
    private val locationTypeRepository: LocationTypeRepository,
    private val areaRepository: AreaRepository,
    private val zoneRepository: ZoneRepository,
    private val locationClusterRepository: LocationClusterRepository,
    private val outboxService: OutboxService,
    private val zoneService: ZoneService,
    private val areaService: AreaService,
    private val locationTypeService: LocationTypeService,
    private val locationClusterService: LocationClusterService,
    private val stockUnitLookup: StockUnitLookup,
) {
    private val log = Logger.getLogger(LocationService::class.java)

    fun listLocations(clientId: Long): List<LocationResponse> =
        locationRepository.findByClientId(clientId).map { toLocationResponse(it) }

    fun listByArea(areaId: Long, clientId: Long): List<LocationResponse> =
        locationRepository.findByArea(areaId, clientId).map { toLocationResponse(it) }

    fun listByZone(zoneId: Long, clientId: Long): List<LocationResponse> =
        locationRepository.findByZone(zoneId, clientId).map { toLocationResponse(it) }

    fun listLocationsPaginated(
        clientId: Long,
        areaId: Long?,
        zoneId: Long?,
        plcCode: String?,
        pagination: PaginationParams,
    ): PaginatedResponse<LocationResponse> {
        val sort = SortParser.parse(pagination.sort, SORTABLE_FIELDS, Sort.ascending("orderIndex"))
        val query = locationRepository.search(clientId, areaId, zoneId, plcCode, sort)
            .page(Page.of(pagination.page, pagination.size))
        val content = query.list().map { toLocationResponse(it) }
        val totalElements = query.count()
        return paginatedResponse(content, pagination.page, pagination.size, totalElements)
    }

    @CacheResult(cacheName = "locations-by-id")
    fun findById(@CacheKey id: Long, @CacheKey clientId: Long): LocationResponse {
        val entity = findEntityById(id, clientId)
        return toLocationResponse(entity)
    }

    @CacheResult(cacheName = "locations-by-scan-code")
    fun findByScanCode(@CacheKey scanCode: String, @CacheKey clientId: Long): LocationResponse {
        val entity = locationRepository.findByScanCode(scanCode, clientId)
            ?: throw LayoutException.NotFound("StorageLocation", "scanCode=$scanCode")
        return toLocationResponse(entity)
    }

    /**
     * `clientId` stamps whatever tenant the caller authenticated as, including 0, the SYS/OPS
     * sentinel. An OPS principal creating a location therefore mints a **shared** location by
     * design: ops IS client 0 (see the `clients` table doctrine, `isSystemClient` derives from
     * `id == 0`), and a shared location is the natural kind for ops to create (a bin no single
     * goods owner should be considered to exclusively hold). This is deliberate, not an
     * oversight; it is the acceptance behind row 17 (defect-burndown-4, Task 11) flipping
     * `onlyClientLocation` to default `true`: reaching a shared location is now an explicit
     * per-strategy opt-in for putaway, while ops-created shared locations remain freely
     * creatable and stay reachable to any tenant whose strategy opts in.
     */
    @Suppress("ThrowsCount")
    @Transactional
    fun createLocation(request: CreateLocationRequest, clientId: Long): LocationResponse {
        // 1. Validate name uniqueness within tenant
        locationRepository.findByName(request.name, clientId)?.let {
            throw LayoutException.DuplicateName("StorageLocation", request.name)
        }

        // 2. Validate locationTypeId
        val locationType = locationTypeRepository.findById(request.locationTypeId)
            ?: throw LayoutException.InvalidReference("StorageLocation", "LocationType", request.locationTypeId)

        // 3. Validate areaId
        val area = areaRepository.findById(request.areaId)
            ?: throw LayoutException.InvalidReference("StorageLocation", "Area", request.areaId)

        // 4. Validate zoneId if provided
        val zone = request.zoneId?.let {
            zoneRepository.findById(it)
                ?: throw LayoutException.InvalidReference("StorageLocation", "Zone", it)
        }

        // 5. Validate locationClusterId if provided
        val cluster = request.locationClusterId?.let {
            locationClusterRepository.findById(it)
                ?: throw LayoutException.InvalidReference("StorageLocation", "LocationCluster", it)
        }

        // 6. Create entity
        val entity = StorageLocation().apply {
            name = request.name
            scanCode = request.scanCode ?: request.name
            this.locationType = locationType
            this.area = area
            this.zone = zone
            this.locationCluster = cluster
            orderIndex = request.orderIndex
            xPos = request.xPos
            yPos = request.yPos
            zPos = request.zPos
            rack = request.rack
            field = request.field
            section = request.section
            plcCode = request.plcCode
            allocationState = request.allocationState
            capacity = request.capacity
            temperatureZone = request.temperatureZone
            handlingClass = request.handlingClass
            kind = request.kind
            isClearing = request.isClearing
            this.clientId = clientId
        }

        // 7. Persist. IDENTITY generation means persist() issues the INSERT immediately
        // (not deferred to flush) -- so isClearing's partial unique index (V310) fires
        // right here if another location already holds it, same 409 mapping as updateLocation.
        try {
            locationRepository.persist(entity)
        } catch (@Suppress("SwallowedException") e: PersistenceException) {
            throw LayoutException.LocationInUse(
                0,
                "another location is already configured as the clearing location",
            )
        }

        // 8. Return response
        return toLocationResponse(entity)
    }

    @Suppress("CyclomaticComplexMethod", "ThrowsCount")
    @Transactional
    fun updateLocation(id: Long, request: UpdateLocationRequest, clientId: Long): LocationResponse {
        val entity = findEntityById(id, clientId)

        request.scanCode?.let { entity.scanCode = it }
        request.locationTypeId?.let {
            entity.locationType = locationTypeRepository.findById(it)
                ?: throw LayoutException.InvalidReference("StorageLocation", "LocationType", it)
        }
        request.areaId?.let {
            entity.area = areaRepository.findById(it)
                ?: throw LayoutException.InvalidReference("StorageLocation", "Area", it)
        }
        request.zoneId?.let {
            entity.zone = zoneRepository.findById(it)
                ?: throw LayoutException.InvalidReference("StorageLocation", "Zone", it)
        }
        request.locationClusterId?.let {
            entity.locationCluster = locationClusterRepository.findById(it)
                ?: throw LayoutException.InvalidReference("StorageLocation", "LocationCluster", it)
        }
        request.orderIndex?.let { entity.orderIndex = it }
        request.xPos?.let { entity.xPos = it }
        request.yPos?.let { entity.yPos = it }
        request.zPos?.let { entity.zPos = it }
        request.rack?.let { entity.rack = it }
        request.field?.let { entity.field = it }
        request.section?.let { entity.section = it }
        request.plcCode?.let { entity.plcCode = it }
        request.allocationState?.let { entity.allocationState = it }
        request.capacity?.let { entity.capacity = it }
        request.temperatureZone?.let { entity.temperatureZone = it }
        request.handlingClass?.let { entity.handlingClass = it }
        request.kind?.let { entity.kind = it }
        request.isClearing?.let {
            entity.isClearing = it
            // A2-1: the partial unique index (V310) only fires at flush/commit, which
            // would otherwise happen after this method returns (past any try-catch here)
            // and surface as a raw 500. Force the flush now, inside the method, so the
            // constraint violation can be caught and mapped to a clean 409.
            try {
                locationRepository.flush()
            } catch (@Suppress("SwallowedException") e: PersistenceException) {
                // KaryoException carries no cause chain (see libs/karyo-common), so the
                // original PersistenceException can't be attached -- same accepted tradeoff
                // as toLocationResponse's IllegalArgumentException catch below.
                throw LayoutException.LocationInUse(
                    id,
                    "another location is already configured as the clearing location",
                )
            }
        }

        invalidateById(id, clientId)
        invalidateByScanCode(entity.scanCode ?: entity.name, clientId)
        return toLocationResponse(entity)
    }

    @Transactional
    fun lockLocation(id: Long, request: LockLocationRequest, clientId: Long): LocationResponse {
        val entity = findEntityById(id, clientId)

        // Validate target lockType
        val targetLock = LockType.fromCode(request.lockType)

        // Capture old lockType
        val oldLockType = entity.lockType

        // Update entity
        entity.lockType = targetLock.code

        // Publish outbox event
        outboxService.publish(
            "StorageLocation",
            entity.id!!,
            "LocationLockChanged",
            LocationLockChangedEvent(
                locationId = entity.id!!,
                locationName = entity.name,
                oldLockType = oldLockType,
                newLockType = targetLock.code,
                clientId = clientId,
            ),
            clientId,
        )

        invalidateById(id, clientId)
        invalidateByScanCode(entity.scanCode ?: entity.name, clientId)
        return toLocationResponse(entity)
    }

    /**
     * Answers "may a unit load weighing [proposedWeight] be added to location [id]?" against the
     * location type's `liftingCapacity`, counting **what is already resting there**.
     *
     * Current weight is computed ON DEMAND from
     * [StockUnitLookup.grossWeightByLocationIds] -- one query summing
     * [com.karyo.inventory.domain.model.UnitLoad.weight] per unit load physically present at
     * the location. There is no weight cache table and none is needed: this is the same read
     * [GroupCapacityReader] already uses for the field/section group caps, so the single-location
     * check and the group check now agree on both the number and where it comes from. (The
     * pre-fix code hard-coded `currentWeight = BigDecimal.ZERO` with a "v1: no weight cache
     * table" comment, which made this method compare the incoming load against the cap in
     * isolation -- it could never refuse a load onto an already-loaded rack, so capacity
     * enforcement did not enforce.)
     *
     * **Deliberately unscoped by tenant**, on both halves: the entity read here (same trust
     * model as [updateAllocation]/[markCounted] -- trusted in-process callers, no external REST
     * route) and the weight read, which carries its own ruling on
     * [StockUnitLookup.grossWeightByLocationIds]. A pallet presses on the rack whoever owns the
     * goods, so narrowing either read to the caller's `client_id` would silently drop another
     * owner's pallets from the load figure and under-count real weight -- the failure mode is a
     * physical overload, not a privacy leak. Do not "fix" this into a scoped read.
     *
     * **Honest degradation (inherited from the SPI):** a unit load that was never weighed
     * contributes ZERO, and a location holding nothing weighed reads as zero occupied weight.
     * The check then degrades to cap-vs-incoming-weight -- exactly the pre-fix behaviour, but
     * only where no weight data exists at all, rather than always. It never refuses a load on
     * occupancy it cannot see.
     *
     * **In-flight putaway demand is NOT counted here**, by the same division of concerns
     * [StockUnitLookup.occupancyByLocationIds] records: a putaway that has already claimed this
     * destination bumps the location's `allocation` (the layout module's own `LocationReservation`
     * soft-reserve), never its stock-level weight. So this answers "what is physically resting
     * there", not "what is committed to land there" - a caller that needs both reads the
     * allocation too.
     *
     * Only a null `liftingCapacity` is unlimited here: an explicit zero is a real cap of zero and
     * refuses any positive weight. Filter 5 of
     * [com.karyo.layout.repository.StorageLocationRepository.findPutawayCandidates] reads a zero
     * cap as unlimited instead, so the two disagree on that one value; the disagreement is
     * recorded, not resolved here.
     */
    @Transactional
    fun checkCapacity(id: Long, proposedWeight: BigDecimal): CheckCapacityResponse {
        val entity = locationRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageLocation", id)

        val liftingCapacity = entity.locationType.liftingCapacity
        val currentWeight = stockUnitLookup.grossWeightByLocationIds(setOf(id))[id] ?: BigDecimal.ZERO

        if (liftingCapacity == null) {
            return CheckCapacityResponse(
                allowed = true,
                currentWeight = currentWeight,
                proposedWeight = proposedWeight,
                liftingCapacity = null,
                reason = null,
            )
        }

        val totalWeight = currentWeight + proposedWeight
        val allowed = totalWeight <= liftingCapacity
        return CheckCapacityResponse(
            allowed = allowed,
            currentWeight = currentWeight,
            proposedWeight = proposedWeight,
            liftingCapacity = liftingCapacity,
            reason = if (!allowed) {
                "Proposed weight $proposedWeight on top of current weight $currentWeight " +
                    "exceeds lifting capacity $liftingCapacity"
            } else {
                null
            },
        )
    }

    /**
     * Update allocation for a location. Called by UnitLoadTransferredObserver.
     * Looks up location by ID without tenant scope (since locationId comes from the inventory module's event),
     * reads clientId from entity for outbox event.
     */
    @Transactional
    fun updateAllocation(locationId: Long, delta: BigDecimal) {
        val entity = locationRepository.findById(locationId)
        if (entity == null) {
            log.warn("Location $locationId not found for allocation update, skipping")
            return
        }

        val oldAllocation = entity.allocation
        val newAllocation = (oldAllocation + delta).coerceIn(BigDecimal.ZERO, BigDecimal("100"))
        entity.allocation = newAllocation

        outboxService.publish(
            "StorageLocation",
            locationId,
            "LocationAllocationChanged",
            LocationAllocationChangedEvent(
                locationId = locationId,
                locationName = entity.name,
                oldAllocation = oldAllocation,
                newAllocation = newAllocation,
                clientId = entity.clientId,
            ),
            entity.clientId,
        )

        invalidateById(locationId, entity.clientId)
        invalidateByScanCode(entity.scanCode ?: entity.name, entity.clientId)
    }

    @Transactional
    fun delete(id: Long, clientId: Long) {
        val entity = findEntityById(id, clientId)
        invalidateById(id, clientId)
        invalidateByScanCode(entity.scanCode ?: entity.name, clientId)
        locationRepository.deleteById(id)
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "locations-by-id")
    fun invalidateById(@CacheKey id: Long, @CacheKey clientId: Long) {
        // Cache invalidation handled by annotation
    }

    @Suppress("UnusedParameter")
    @CacheInvalidate(cacheName = "locations-by-scan-code")
    fun invalidateByScanCode(@CacheKey scanCode: String, @CacheKey clientId: Long) {
        // Cache invalidation handled by annotation
    }

    private fun findEntityById(id: Long, clientId: Long): StorageLocation {
        val entity = locationRepository.findById(id)
            ?: throw LayoutException.NotFound("StorageLocation", id)
        if (entity.clientId != clientId) {
            throw LayoutException.NotFound("StorageLocation", id)
        }
        return entity
    }

    companion object {
        val SORTABLE_FIELDS = setOf("id", "name", "scanCode", "orderIndex", "created")
    }

    fun toLocationResponse(entity: StorageLocation): LocationResponse {
        val lockTypeName = try {
            LockType.fromCode(entity.lockType).name
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            "UNKNOWN"
        }

        return LocationResponse(
            id = entity.id!!,
            name = entity.name,
            scanCode = entity.scanCode,
            locationType = locationTypeService.toLocationTypeResponse(entity.locationType),
            area = areaService.toAreaResponse(entity.area),
            zone = entity.zone?.let { zoneService.toZoneResponse(it) },
            locationCluster = entity.locationCluster?.let { locationClusterService.toClusterResponse(it) },
            allocation = entity.allocation,
            lockType = entity.lockType,
            lockTypeName = lockTypeName,
            orderIndex = entity.orderIndex,
            xPos = entity.xPos,
            yPos = entity.yPos,
            zPos = entity.zPos,
            rack = entity.rack,
            field = entity.field,
            section = entity.section,
            created = entity.created.toString(),
            modified = entity.modified.toString(),
            capacity = entity.capacity,
            temperatureZone = entity.temperatureZone,
            handlingClass = entity.handlingClass,
            kind = entity.kind,
            lastCountedAt = entity.lastCountedAt?.toString(),
            isClearing = entity.isClearing,
            plcCode = entity.plcCode,
            allocationState = entity.allocationState,
        )
    }

    /**
     * L3 (locations-layout sprint, Task 6): the real `lastCountedAt` writer — reached via
     * [com.karyo.layout.spi.LocationLockPort.markCounted], called by the stocktaking module's
     * `StocktakingService.finishOrder` (shared by both the discrepancy-accept path and the
     * no-discrepancy auto-finish path) for exactly the order's counted location(s), at
     * accept/finish time. **Unscoped internal write** — same trust model as
     * [updateAllocation]/[checkCapacity]: the caller is trusted in-process code, not an
     * external REST caller, so there is no tenant check here.
     *
     * Deliberately NOT called anywhere else — myWMS also stamps `stockTakingDate` on pick from
     * the location; Karyo deliberately diverges on that pick-time stamp (WORKLIST follow-up
     * note), so a location that is only ever picked from (never cycle-counted) keeps
     * `lastCountedAt == null` forever, which is the honest state, not a bug.
     */
    @Transactional
    fun markCounted(locationIds: List<Long>, at: Instant) {
        locationIds.forEach { id ->
            val entity = locationRepository.findById(id)
            if (entity == null) {
                log.warn("Location $id not found for markCounted, skipping")
                return@forEach
            }
            entity.lastCountedAt = at
            invalidateById(id, entity.clientId)
            invalidateByScanCode(entity.scanCode ?: entity.name, entity.clientId)
        }
    }
}

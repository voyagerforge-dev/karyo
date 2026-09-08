package com.karyo.layout.service

import com.karyo.events.outbox.OutboxService
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.domain.model.Area
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.dto.CheckCapacityRequest
import com.karyo.layout.dto.CreateLocationRequest
import com.karyo.layout.dto.LockLocationRequest
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.*
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T

@QuarkusTest
class LocationServiceTest {

    @InjectMock
    lateinit var locationRepository: StorageLocationRepository

    @InjectMock
    lateinit var locationTypeRepository: LocationTypeRepository

    @InjectMock
    lateinit var areaRepository: AreaRepository

    @InjectMock
    lateinit var zoneRepository: ZoneRepository

    @InjectMock
    lateinit var locationClusterRepository: LocationClusterRepository

    @InjectMock
    lateinit var outboxService: OutboxService

    /** checkCapacity now reads real occupied weight through this SPI. Mocked so these two
     * scenarios stay about the cap arithmetic on an EMPTY location: Mockito's default answer
     * for a `Map` return is an empty map, which the service reads as zero occupied weight.
     * Occupied-rack behaviour lives in [LocationServiceCheckCapacityTest] and
     * [LocationServiceCapacityEnforcementTest]. */
    @InjectMock
    lateinit var stockUnitLookup: StockUnitLookup

    @Inject
    lateinit var locationService: LocationService

    private fun buildLocationType(id: Long = 1L, name: String = "Pallet Rack"): LocationType {
        val lt = LocationType()
        lt.id = id
        lt.name = name
        lt.liftingCapacity = BigDecimal("1000")
        lt.created = Instant.now()
        lt.modified = Instant.now()
        return lt
    }

    private fun buildArea(id: Long = 1L, name: String = "Storage Area"): Area {
        val a = Area()
        a.id = id
        a.name = name
        a.usages = "STORAGE"
        a.created = Instant.now()
        a.modified = Instant.now()
        return a
    }

    private fun buildLocation(
        id: Long = 1L,
        name: String = "A-01-01",
        clientId: Long = 1L,
        locationType: LocationType = buildLocationType(),
        area: Area = buildArea(),
    ): StorageLocation {
        val loc = StorageLocation()
        loc.id = id
        loc.name = name
        loc.scanCode = name
        loc.locationType = locationType
        loc.area = area
        loc.allocation = BigDecimal.ZERO
        loc.lockType = 0
        loc.orderIndex = 0
        loc.xPos = 0
        loc.yPos = 0
        loc.zPos = 0
        loc.clientId = clientId
        loc.created = Instant.now()
        loc.modified = Instant.now()
        return loc
    }

    private fun mockPersistWithIdAssignment(assignedId: Long = 100L) {
        doAnswer { invocation ->
            val entity = invocation.getArgument<StorageLocation>(0)
            if (entity.id == null) {
                entity.id = assignedId
            }
            null
        }.`when`(locationRepository).persist(anyObj<StorageLocation>())
    }

    @BeforeEach
    fun setup() {
        // Common setup if needed
    }

    // ── 1. Create location with valid data returns LocationResponse with default scanCode ──

    @Test
    fun `createLocation with valid data returns LocationResponse with scanCode defaulting to name`() {
        val lt = buildLocationType()
        val area = buildArea()

        `when`(locationRepository.findByName("A-01-01", 1L)).thenReturn(null)
        `when`(locationTypeRepository.findById(1L)).thenReturn(lt)
        `when`(areaRepository.findById(1L)).thenReturn(area)
        mockPersistWithIdAssignment()

        val request = CreateLocationRequest(
            name = "A-01-01",
            locationTypeId = 1L,
            areaId = 1L,
        )

        val result = locationService.createLocation(request, 1L)

        assertThat(result.name).isEqualTo("A-01-01")
        assertThat(result.scanCode).isEqualTo("A-01-01") // defaults to name
        assertThat(result.locationType.name).isEqualTo("Pallet Rack")
        assertThat(result.area.name).isEqualTo("Storage Area")
    }

    // ── 2. Create location with custom scanCode preserves it ──

    @Test
    fun `createLocation with custom scanCode preserves it`() {
        val lt = buildLocationType()
        val area = buildArea()

        `when`(locationRepository.findByName("A-01-02", 1L)).thenReturn(null)
        `when`(locationTypeRepository.findById(1L)).thenReturn(lt)
        `when`(areaRepository.findById(1L)).thenReturn(area)
        mockPersistWithIdAssignment()

        val request = CreateLocationRequest(
            name = "A-01-02",
            scanCode = "CUSTOM-SCAN",
            locationTypeId = 1L,
            areaId = 1L,
        )

        val result = locationService.createLocation(request, 1L)

        assertThat(result.scanCode).isEqualTo("CUSTOM-SCAN")
    }

    // ── 3. Create location with duplicate name throws DuplicateName ──

    @Test
    fun `createLocation with duplicate name throws DuplicateName`() {
        val existing = buildLocation()
        `when`(locationRepository.findByName("A-01-01", 1L)).thenReturn(existing)

        val request = CreateLocationRequest(
            name = "A-01-01",
            locationTypeId = 1L,
            areaId = 1L,
        )

        assertThatThrownBy { locationService.createLocation(request, 1L) }
            .isInstanceOf(LayoutException.DuplicateName::class.java)
    }

    // ── 4. Create location with invalid locationTypeId throws InvalidReference ──

    @Test
    fun `createLocation with invalid locationTypeId throws InvalidReference`() {
        `when`(locationRepository.findByName("A-02-01", 1L)).thenReturn(null)
        `when`(locationTypeRepository.findById(999L)).thenReturn(null)

        val request = CreateLocationRequest(
            name = "A-02-01",
            locationTypeId = 999L,
            areaId = 1L,
        )

        assertThatThrownBy { locationService.createLocation(request, 1L) }
            .isInstanceOf(LayoutException.InvalidReference::class.java)
    }

    // ── 5. Lock location from UNLOCKED to STOCKTAKING succeeds and publishes event ──

    @Test
    fun `lockLocation from UNLOCKED to STOCKTAKING succeeds and publishes outbox event`() {
        val entity = buildLocation()
        `when`(locationRepository.findById(1L)).thenReturn(entity)

        val request = LockLocationRequest(lockType = 7) // STOCKTAKING

        val result = locationService.lockLocation(1L, request, 1L)

        assertThat(result.lockType).isEqualTo(7)
        assertThat(result.lockTypeName).isEqualTo("STOCKTAKING")
        verifyOutboxPublished(1)
    }

    // ── 6. Lock location with invalid lock code throws ──

    @Test
    fun `lockLocation with invalid lock code throws IllegalArgumentException`() {
        val entity = buildLocation()
        `when`(locationRepository.findById(1L)).thenReturn(entity)

        val request = LockLocationRequest(lockType = 99) // Invalid

        assertThatThrownBy { locationService.lockLocation(1L, request, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── 7. findByScanCode returns cached result ──

    @Test
    fun `findByScanCode returns location`() {
        val entity = buildLocation()
        `when`(locationRepository.findByScanCode("A-01-01", 1L)).thenReturn(entity)

        val result = locationService.findByScanCode("A-01-01", 1L)

        assertThat(result.name).isEqualTo("A-01-01")
        assertThat(result.scanCode).isEqualTo("A-01-01")
    }

    // ── 8. checkCapacity with weight under limit returns allowed=true ──

    @Test
    fun `checkCapacity with weight under limit returns allowed true`() {
        val lt = buildLocationType()
        lt.liftingCapacity = BigDecimal("1000")
        val entity = buildLocation(locationType = lt)
        `when`(locationRepository.findById(1L)).thenReturn(entity)

        val result = locationService.checkCapacity(1L, BigDecimal("500"))

        assertThat(result.allowed).isTrue()
        assertThat(result.reason).isNull()
    }

    // ── 9b. toLocationResponse carries the Phase B metadata fields (B9/B10/B12) ──

    @Test
    fun `toLocationResponse carries capacity, temperatureZone, handlingClass, kind and lastCountedAt`() {
        val counted = Instant.now()
        val entity = buildLocation().apply {
            capacity = 6
            temperatureZone = "CHILLED"
            handlingClass = "HAZMAT"
            kind = "RESERVE"
            lastCountedAt = counted
        }

        val result = locationService.toLocationResponse(entity)

        assertThat(result.capacity).isEqualTo(6)
        assertThat(result.temperatureZone).isEqualTo("CHILLED")
        assertThat(result.handlingClass).isEqualTo("HAZMAT")
        assertThat(result.kind).isEqualTo("RESERVE")
        assertThat(result.lastCountedAt).isEqualTo(counted.toString())
    }

    @Test
    fun `toLocationResponse honestly nulls out unset Phase B metadata`() {
        val entity = buildLocation() // capacity/temperatureZone/handlingClass/kind/lastCountedAt left null

        val result = locationService.toLocationResponse(entity)

        assertThat(result.capacity).isNull()
        assertThat(result.temperatureZone).isNull()
        assertThat(result.handlingClass).isNull()
        assertThat(result.kind).isNull()
        assertThat(result.lastCountedAt).isNull()
    }

    // ── 9. checkCapacity with weight over limit returns allowed=false ──

    @Test
    fun `checkCapacity with weight over limit returns allowed false with reason`() {
        val lt = buildLocationType()
        lt.liftingCapacity = BigDecimal("1000")
        val entity = buildLocation(locationType = lt)
        `when`(locationRepository.findById(1L)).thenReturn(entity)

        val result = locationService.checkCapacity(1L, BigDecimal("1500"))

        assertThat(result.allowed).isFalse()
        assertThat(result.reason).contains("exceeds")
    }

    /**
     * Verifies OutboxService.publish was invoked the expected number of times.
     * Uses invocation inspection to avoid Mockito matcher null issues with Kotlin primitives.
     */
    private fun verifyOutboxPublished(expectedTimes: Int = 1) {
        val invocations = org.mockito.Mockito.mockingDetails(outboxService).invocations
        val publishCalls = invocations.filter { it.method.name == "publish" }
        assertThat(publishCalls).hasSize(expectedTimes)
    }
}

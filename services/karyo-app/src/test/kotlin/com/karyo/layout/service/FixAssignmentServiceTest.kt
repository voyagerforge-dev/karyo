package com.karyo.layout.service

import com.karyo.inventory.api.dto.StockUnitResponse
import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.domain.model.Area
import com.karyo.layout.domain.model.FixAssignment
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.dto.CreateFixAssignmentRequest
import com.karyo.layout.exception.LayoutException
import com.karyo.layout.repository.FixAssignmentRepository
import com.karyo.layout.repository.StorageLocationRepository
import com.karyo.product.dto.ItemUnitResponse
import com.karyo.product.dto.ProductResponse
import com.karyo.product.spi.ProductLookup
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant

@Suppress("UNCHECKED_CAST")
private fun <T> anyObj(): T = ArgumentMatchers.any<T>() ?: null as T

/**
 * Tests FixAssignmentService against the in-process SPI lookups
 * (ProductLookup / StockUnitLookup) that replaced the former REST clients.
 */
@QuarkusTest
class FixAssignmentServiceTest {

    @InjectMock
    lateinit var fixAssignmentRepository: FixAssignmentRepository

    @InjectMock
    lateinit var locationRepository: StorageLocationRepository

    @InjectMock
    lateinit var productLookup: ProductLookup

    @InjectMock
    lateinit var stockUnitLookup: StockUnitLookup

    @Inject
    lateinit var fixAssignmentService: FixAssignmentService

    private fun buildLocation(id: Long = 1L, clientId: Long = 1L): StorageLocation {
        val lt = LocationType().apply {
            this.id = 1L
            name = "Pallet Rack"
            created = Instant.now()
            modified = Instant.now()
        }
        val area = Area().apply {
            this.id = 1L
            name = "Storage"
            created = Instant.now()
            modified = Instant.now()
        }
        return StorageLocation().apply {
            this.id = id
            name = "A-01-01"
            scanCode = "A-01-01"
            locationType = lt
            this.area = area
            allocation = BigDecimal.ZERO
            lockType = 0
            orderIndex = 0
            xPos = 0; yPos = 0; zPos = 0
            this.clientId = clientId
            created = Instant.now()
            modified = Instant.now()
        }
    }

    private fun buildProduct(id: Long = 42L, number: String = "SKU-042", state: Int = 100) = ProductResponse(
        id = id,
        number = number,
        name = "Widget",
        description = null,
        state = state,
        itemUnit = ItemUnitResponse(id = 1L, name = "PCS", unitType = "PIECE"),
        scale = 0,
        weight = null, height = null, width = null, depth = null, volume = null,
        lotMandatory = false,
        bestBeforeMandatory = false,
        shelflife = null,
        serialNoRecordType = "NO_RECORD",
        defaultUnitLoadTypeId = null,
        defaultStorageStrategyId = null,
        zoneId = null,
        tradeGroup = null,
        imageUrl = null,
        numbers = emptyList(),
        packagingUnits = emptyList(),
        created = "2026-03-02T00:00:00Z",
        modified = "2026-03-02T00:00:00Z",
    )

    private fun buildStockUnit(id: Long, amount: BigDecimal, locationId: Long, state: Int = 300) = StockUnitResponse(
        id = id,
        itemDataId = 42L,
        itemDataNumber = "SKU-042",
        itemDataName = "Widget",
        amount = amount,
        reservedAmount = BigDecimal.ZERO,
        availableAmount = amount,
        serialNumber = null,
        lotNumber = null,
        packagingUnitId = null,
        bestBefore = null,
        state = state,
        stateName = "ON_STOCK",
        lockType = 0,
        lockTypeName = "UNLOCKED",
        strategyDate = null,
        unitLoadId = 1L,
        unitLoadLabel = "UL-001",
        locationId = locationId,
        locationName = "A-01-01",
        created = Instant.now(),
        modified = Instant.now(),
        supplierName = null,
        sourceAsn = null,
        receivedAt = null,
        aggregateStocks = false,
    )

    private fun mockPersistWithIdAssignment(assignedId: Long = 100L) {
        doAnswer { invocation ->
            val entity = invocation.getArgument<FixAssignment>(0)
            if (entity.id == null) {
                entity.id = assignedId
            }
            null
        }.`when`(fixAssignmentRepository).persist(anyObj<FixAssignment>())
    }

    // ── 1. Create with valid product (active) succeeds ──

    @Test
    fun `create with active product succeeds with denormalized itemDataNumber`() {
        val location = buildLocation()
        `when`(locationRepository.findById(1L)).thenReturn(location)
        `when`(fixAssignmentRepository.findByLocationAndItem(1L, 42L)).thenReturn(null)
        `when`(productLookup.findById(42L)).thenReturn(buildProduct(state = 100))
        `when`(stockUnitLookup.findByItemDataId(42L, 1L)).thenReturn(emptyList())
        mockPersistWithIdAssignment()

        val request = CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L)
        val result = fixAssignmentService.create(request, 1L)

        assertThat(result.itemDataNumber).isEqualTo("SKU-042")
        assertThat(result.locationId).isEqualTo(1L)
        assertThat(result.itemDataId).isEqualTo(42L)
    }

    // ── 2. Create with inactive product throws ProductValidationFailed ──

    @Test
    fun `create with inactive product throws ProductValidationFailed`() {
        val location = buildLocation()
        `when`(locationRepository.findById(1L)).thenReturn(location)
        `when`(fixAssignmentRepository.findByLocationAndItem(1L, 42L)).thenReturn(null)
        `when`(productLookup.findById(42L)).thenReturn(buildProduct(state = 700))

        val request = CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L)

        assertThatThrownBy { fixAssignmentService.create(request, 1L) }
            .isInstanceOf(LayoutException.ProductValidationFailed::class.java)
    }

    // ── 3. Create with duplicate (location, product) throws DuplicateName ──

    @Test
    fun `create with duplicate location-product pair throws DuplicateName`() {
        val location = buildLocation()
        val existing = FixAssignment().apply {
            id = 10L
            this.location = location
            itemDataId = 42L
            created = Instant.now()
            modified = Instant.now()
        }
        `when`(locationRepository.findById(1L)).thenReturn(location)
        `when`(fixAssignmentRepository.findByLocationAndItem(1L, 42L)).thenReturn(existing)

        val request = CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L)

        assertThatThrownBy { fixAssignmentService.create(request, 1L) }
            .isInstanceOf(LayoutException.DuplicateName::class.java)
    }

    // ── 4. Create with unknown product throws InvalidReference ──
    // (Formerly "product service unavailable" -- with the in-process lookup there is
    // no transport failure mode; an unknown/foreign-tenant product resolves to null.)

    @Test
    fun `create with unknown product throws InvalidReference`() {
        val location = buildLocation()
        `when`(locationRepository.findById(1L)).thenReturn(location)
        `when`(fixAssignmentRepository.findByLocationAndItem(1L, 42L)).thenReturn(null)
        `when`(productLookup.findById(42L)).thenReturn(null)

        val request = CreateFixAssignmentRequest(locationId = 1L, itemDataId = 42L)

        assertThatThrownBy { fixAssignmentService.create(request, 1L) }
            .isInstanceOf(LayoutException.InvalidReference::class.java)
    }

    // ── 5. enrichStockAmount returns sum of matching stock amounts ──

    @Test
    fun `enrichStockAmount returns sum of stock amounts for location`() {
        `when`(stockUnitLookup.findByItemDataId(42L, 1L)).thenReturn(
            listOf(
                buildStockUnit(id = 1, amount = BigDecimal("10.5"), locationId = 1L),
                buildStockUnit(id = 2, amount = BigDecimal("5.5"), locationId = 1L),
                buildStockUnit(id = 3, amount = BigDecimal("20"), locationId = 2L), // different location
            )
        )

        val result = fixAssignmentService.enrichStockAmount(42L, 1L, 1L)

        assertThat(result).isEqualByComparingTo(BigDecimal("16.0"))
    }

    // ── 6. enrichStockAmount counts ON_STOCK only, not PICKED/other states ──

    @Test
    fun `enrichStockAmount sums ON_STOCK stock only, excluding PICKED`() {
        `when`(stockUnitLookup.findByItemDataId(42L, 1L)).thenReturn(
            listOf(
                buildStockUnit(id = 1, amount = BigDecimal("10"), locationId = 1L, state = 300), // ON_STOCK
                buildStockUnit(id = 2, amount = BigDecimal("5"), locationId = 1L, state = 600), // PICKED
            )
        )

        val result = fixAssignmentService.enrichStockAmount(42L, 1L, 1L)

        assertThat(result).isEqualByComparingTo(BigDecimal("10"))
    }
}

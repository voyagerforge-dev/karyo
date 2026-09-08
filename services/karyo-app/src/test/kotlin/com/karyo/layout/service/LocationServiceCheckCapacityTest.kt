package com.karyo.layout.service

import com.karyo.inventory.api.spi.StockUnitLookup
import com.karyo.layout.domain.model.LocationType
import com.karyo.layout.domain.model.StorageLocation
import com.karyo.layout.repository.StorageLocationRepository
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import java.math.BigDecimal

/**
 * Exercises [LocationService.checkCapacity] directly - no REST round-trip. This used to go
 * through the (now deleted) `/api/internal/locations/{id}/check-capacity` router; converted 1:1,
 * same scenarios/assertions. Previously the deleted `InternalLocationResourceTest` mocked
 * [LocationService] itself to verify the resource's pass-through; now that the resource is gone,
 * this exercises the real service logic against a mocked repository instead.
 *
 * Branch coverage for the fix that made the check count what is ALREADY at the location
 * ([StockUnitLookup.grossWeightByLocationIds] instead of a hard-coded zero). The
 * already-loaded-rack behaviour is pinned end to end against real persisted stock by
 * [LocationServiceCapacityEnforcementTest] - this file stays on mocks so each branch (absent
 * entry, null cap, zero cap, exact boundary) can be driven exactly.
 */
@QuarkusTest
class LocationServiceCheckCapacityTest {

    @InjectMock
    lateinit var locationRepository: StorageLocationRepository

    /** Mocked so each test states the location's occupied weight outright. Mockito's default
     * answer for a `Map` return is an EMPTY map, which the service reads as zero occupied
     * weight - the honest-degradation path, and what the two original scenarios below assume. */
    @InjectMock
    lateinit var stockUnitLookup: StockUnitLookup

    @Inject
    lateinit var locationService: LocationService

    private fun buildLocationType(liftingCapacity: BigDecimal? = BigDecimal("1000")): LocationType {
        val lt = LocationType()
        lt.id = 1L
        lt.name = "Pallet Rack"
        lt.liftingCapacity = liftingCapacity
        return lt
    }

    private fun buildLocation(locationType: LocationType): StorageLocation {
        val loc = StorageLocation()
        loc.id = 1L
        loc.name = "A-01-01"
        loc.scanCode = "A-01-01"
        loc.locationType = locationType
        loc.allocation = BigDecimal.ZERO
        loc.lockType = 0
        loc.orderIndex = 0
        loc.xPos = 0
        loc.yPos = 0
        loc.zPos = 0
        loc.clientId = 1L
        return loc
    }

    /** Seeds the location entity and its occupied weight in one step. `currentWeight = null`
     * leaves the lookup returning an empty map - the "nothing weighed here" degradation path. */
    private fun givenLocation(liftingCapacity: BigDecimal?, currentWeight: BigDecimal? = null) {
        `when`(locationRepository.findById(1L)).thenReturn(buildLocation(buildLocationType(liftingCapacity)))
        `when`(stockUnitLookup.grossWeightByLocationIds(setOf(1L)))
            .thenReturn(currentWeight?.let { mapOf(1L to it) } ?: emptyMap())
    }

    // ── 1. Check capacity with weight under limit returns allowed ──

    @Test
    fun `check-capacity with weight under limit returns allowed true`() {
        givenLocation(liftingCapacity = BigDecimal("1000"))

        val result = locationService.checkCapacity(1L, BigDecimal("500"))

        assertThat(result.allowed).isTrue()
    }

    // ── 2. Check capacity with weight over limit returns not allowed ──

    @Test
    fun `check-capacity with weight over limit returns allowed false`() {
        givenLocation(liftingCapacity = BigDecimal("1000"))

        val result = locationService.checkCapacity(1L, BigDecimal("1500"))

        assertThat(result.allowed).isFalse()
        assertThat(result.reason).contains("exceeds lifting capacity 1000")
    }

    // ── 3. The fix: what is already on the location counts ──

    @Test
    fun `a load that fits on its own is refused once the weight already there is counted`() {
        givenLocation(liftingCapacity = BigDecimal("1000"), currentWeight = BigDecimal("800"))

        val result = locationService.checkCapacity(1L, BigDecimal("500"))

        assertThat(result.allowed)
            .`as`("800 already there + 500 incoming = 1300 over a 1000 cap; the pre-fix code saw only the 500")
            .isFalse()
        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal("800"))
        assertThat(result.reason).isEqualTo(
            "Proposed weight 500 on top of current weight 800 exceeds lifting capacity 1000",
        )
    }

    @Test
    fun `a load that still fits alongside the weight already there is allowed`() {
        givenLocation(liftingCapacity = BigDecimal("1000"), currentWeight = BigDecimal("800"))

        val result = locationService.checkCapacity(1L, BigDecimal("150"))

        assertThat(result.allowed).isTrue()
        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal("800"))
        assertThat(result.reason).isNull()
    }

    @Test
    fun `filling the cap exactly is allowed - the limit is inclusive`() {
        givenLocation(liftingCapacity = BigDecimal("1000"), currentWeight = BigDecimal("800"))

        val result = locationService.checkCapacity(1L, BigDecimal("200"))

        assertThat(result.allowed).isTrue()
    }

    @Test
    fun `one gram past the cap is refused`() {
        givenLocation(liftingCapacity = BigDecimal("1000"), currentWeight = BigDecimal("800"))

        assertThat(locationService.checkCapacity(1L, BigDecimal("200.001")).allowed).isFalse()
    }

    // ── 4. Degradation and unlimited caps ──

    @Test
    fun `a location with nothing weighed on it reports zero occupied weight, never a fabricated one`() {
        givenLocation(liftingCapacity = BigDecimal("1000"))

        val result = locationService.checkCapacity(1L, BigDecimal("500"))

        assertThat(result.currentWeight).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.allowed)
            .`as`("degrades to cap-vs-incoming rather than refusing on occupancy it cannot see")
            .isTrue()
    }

    @Test
    fun `a null lifting capacity is unlimited but still reports the real occupied weight`() {
        givenLocation(liftingCapacity = null, currentWeight = BigDecimal("800"))

        val result = locationService.checkCapacity(1L, BigDecimal("5000"))

        assertThat(result.allowed).isTrue()
        assertThat(result.liftingCapacity).isNull()
        assertThat(result.currentWeight)
            .`as`("an unlimited cap is no reason to report a fabricated zero")
            .isEqualByComparingTo(BigDecimal("800"))
    }

    @Test
    fun `a zero lifting capacity is a real cap of zero and refuses any positive weight`() {
        givenLocation(liftingCapacity = BigDecimal.ZERO, currentWeight = BigDecimal("800"))

        val result = locationService.checkCapacity(1L, BigDecimal("5000"))

        assertThat(result.allowed)
            .`as`("only a null cap is unlimited here; an explicit zero refuses, as it did pre-fix")
            .isFalse()
        assertThat(result.liftingCapacity).isEqualByComparingTo(BigDecimal.ZERO)
    }
}

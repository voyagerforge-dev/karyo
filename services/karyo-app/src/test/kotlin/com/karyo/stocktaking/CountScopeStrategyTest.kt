package com.karyo.stocktaking

import com.karyo.layout.spi.LocationLockPort
import com.karyo.stocktaking.service.ExplicitLocationScope
import com.karyo.stocktaking.spi.CountScopeRequest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CountScopeStrategyTest {
    @Test
    fun `explicit scope returns the request locations plus expanded area locations`() {
        val lockPort = mockk<LocationLockPort>()
        every { lockPort.expandAreaToLocations(99, 1) } returns listOf(10, 11)
        val scope = ExplicitLocationScope(lockPort)
        val locs = scope.resolveLocations(CountScopeRequest(locationIds = listOf(1, 2), areaId = 99), clientId = 1)
        assertThat(locs).containsExactlyInAnyOrder(1, 2, 10, 11)
    }

    // St2: location-name-pattern scope -- unions+dedups with an explicit id.
    @Test
    fun `explicit scope resolves a location name pattern and unions dedups with explicit ids`() {
        val lockPort = mockk<LocationLockPort>()
        every { lockPort.findIdsByNamePattern("A-%", 1) } returns listOf(1, 20, 21)
        val scope = ExplicitLocationScope(lockPort)
        val locs = scope.resolveLocations(
            CountScopeRequest(locationIds = listOf(1, 2), locationNamePattern = "A-%"),
            clientId = 1,
        )
        assertThat(locs).containsExactlyInAnyOrder(1, 2, 20, 21)
    }
}

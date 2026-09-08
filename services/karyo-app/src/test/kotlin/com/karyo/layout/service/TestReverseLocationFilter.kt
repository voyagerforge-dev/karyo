package com.karyo.layout.service

import com.karyo.layout.spi.LocationCandidate
import com.karyo.layout.spi.LocationFilter
import com.karyo.layout.spi.LocationFinderRequest
import jakarta.enterprise.context.ApplicationScoped

/**
 * Test-only [LocationFilter] proving the finder HONORS SPI ordering (the stock-selection
 * FSD lesson). When [enabled], it reverses the candidate list; the finder must then
 * return the filter's first element rather than re-imposing its own allocation/name
 * order. Inert by default ([enabled] = false) so it does not perturb other finder tests
 * sharing the same Quarkus app instance.
 */
@ApplicationScoped
class TestReverseLocationFilter : LocationFilter {

    override fun apply(candidates: List<LocationCandidate>, context: LocationFinderRequest): List<LocationCandidate> =
        if (enabled) candidates.reversed() else candidates

    override fun priority(): Int = 100

    companion object {
        /** Toggled per-test; reset in a finally block. */
        @Volatile
        var enabled: Boolean = false
    }
}

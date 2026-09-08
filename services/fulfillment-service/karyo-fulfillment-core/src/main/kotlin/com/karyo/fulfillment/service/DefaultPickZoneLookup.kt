package com.karyo.fulfillment.service

import com.karyo.fulfillment.spi.PickZoneLookup
import io.quarkus.arc.DefaultBean
import jakarta.enterprise.context.ApplicationScoped

/**
 * Default [PickZoneLookup]: every stock unit maps to no zone (null), collapsing wave batch
 * grouping to a single "UNZONED" group -- the correct fallback when no zone-aware source is
 * registered (i.e. karyo-wave-core hasn't wired its own real implementation yet).
 *
 * [@DefaultBean][io.quarkus.arc.DefaultBean] over the codebase's alternative option
 * (`@Alternative` + `@Priority`, which this repo has ZERO precedent for -- grepped first per the
 * task brief, confirmed empty): a `@DefaultBean` is automatically superseded, with no extra
 * wiring needed on the winning side, the instant ANY other CDI bean implements [PickZoneLookup] --
 * wave-core's future real zone lookup just has to exist as a plain `@ApplicationScoped` bean, no
 * `beans.xml` alternatives-enabling or priority tuning required. `@Alternative` would have required
 * BOTH sides to coordinate (the losing side declaring itself an alternative, the winning side
 * enabling it), which is backwards for a "library ships a safe default, the paid module silently
 * upgrades it" seam.
 */
@DefaultBean
@ApplicationScoped
class DefaultPickZoneLookup : PickZoneLookup {
    override fun zonesByStockUnitIds(stockUnitIds: Set<Long>, clientId: Long): Map<Long, String?> =
        stockUnitIds.associateWith { null }
}

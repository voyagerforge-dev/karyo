package com.karyo.fulfillment

import com.karyo.fulfillment.service.PickDifferenceStrategyResolver
import com.karyo.fulfillment.spi.PickDifferenceContext
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class PickDifferenceResolverTest {

    @Inject lateinit var resolver: PickDifferenceStrategyResolver

    @Test
    fun `LEAVE built-in excludes the short source from re-selection`() {
        val resolution = resolver.resolve(
            PickDifferenceContext(
                sourceStockUnitId = 77L, itemDataId = 4L, shortfall = BigDecimal(10),
                clientId = 1L, pickDifferenceStrategyName = "LEAVE",
            ),
        )
        assertThat(resolution.excludeStockUnitIds).containsExactly(77L)
    }

    @Test
    fun `an unknown strategy name falls through to the built-in`() {
        val resolution = resolver.resolve(
            PickDifferenceContext(
                sourceStockUnitId = 88L, itemDataId = 4L, shortfall = BigDecimal(5),
                clientId = 1L, pickDifferenceStrategyName = "NOT_REGISTERED",
            ),
        )
        assertThat(resolution.excludeStockUnitIds).containsExactly(88L)
    }
}

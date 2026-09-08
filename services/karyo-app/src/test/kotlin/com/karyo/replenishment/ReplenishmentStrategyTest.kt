package com.karyo.replenishment

import com.karyo.replenishment.service.ReplenishmentStrategyResolver
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class ReplenishmentStrategyTest {
    @Inject lateinit var resolver: ReplenishmentStrategyResolver

    @Test
    fun `default MIN_MAX flags below-min and ignores at-or-above-min and null-min`() {
        val s = resolver.resolve()
        assertThat(s.name).isEqualTo("MIN_MAX")
        assertThat(s.needsReplenishment(BigDecimal("4"), BigDecimal("10"), null, BigDecimal("20"))).isTrue
        assertThat(s.needsReplenishment(BigDecimal("10"), BigDecimal("10"), null, BigDecimal("20"))).isFalse
        assertThat(s.needsReplenishment(BigDecimal("4"), null, null, null)).isFalse  // no min configured
    }
}

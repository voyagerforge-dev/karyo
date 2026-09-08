package com.karyo.fulfillment

import com.karyo.fulfillment.service.PackoutStrategyResolver
import com.karyo.fulfillment.spi.PackPick
import com.karyo.fulfillment.spi.PackoutContext
import com.karyo.fulfillment.spi.PackoutResult
import com.karyo.fulfillment.spi.PackoutStrategy
import com.karyo.fulfillment.spi.PlannedShippingUnit
import io.quarkus.test.junit.QuarkusTest
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class PackoutResolverTest {

    @Inject lateinit var resolver: PackoutStrategyResolver

    @Test
    fun `ONE_TO_ONE packs the container into one unit with a line per pick`() {
        val result = resolver.resolve(
            PackoutContext(
                shipmentId = 1, deliveryOrderId = 2, pickContainerUnitLoadId = 9, clientId = 1,
                weight = BigDecimal("2.5"), type = "CARTON", packoutStrategyName = "ONE_TO_ONE",
                picks = listOf(
                    PackPick(11, 7, "SKU-7", BigDecimal(60), "L1", 3),
                    PackPick(12, 7, "SKU-7", BigDecimal(10), null, 4),
                ),
            ),
        )
        assertThat(result.complete).isTrue
        assertThat(result.shippingUnits).hasSize(1)
        assertThat(result.shippingUnits.first().unitLoadId).isEqualTo(9)
        assertThat(result.shippingUnits.first().lines).hasSize(2)
        assertThat(result.shippingUnits.first().lines.first().sourcePickId).isEqualTo(11L)
    }

    @Test
    fun `unknown strategy name falls through to the built-in`() {
        val result = resolver.resolve(
            PackoutContext(
                1, 2, 9, 1, BigDecimal("1.0"), "CARTON", "NOPE-DOES-NOT-EXIST",
                listOf(PackPick(11, 7, "SKU-7", BigDecimal(5), null, 3)),
            ),
        )
        assertThat(result.shippingUnits).hasSize(1)
        assertThat(result.complete).isTrue
    }

    /**
     * Resolver hole (packing-facts.md, outbound-completion sprint Task 3): a named-match strategy
     * whose `pack()` returns null (e.g. gated/unentitled, "defer cheaply") used to fall through to
     * the ordered priority scan, which RE-INVOKED the same named instance a second time before
     * reaching the next candidate. Pure unit test (mocked `Instance<PackoutStrategy>`, no CDI) so
     * a counting fake can pin the exact invocation count -- mirrors PackingServiceTest's
     * mock-based second test for the same reason.
     */
    @Test
    fun `a named strategy that returns null is invoked exactly once, not re-invoked in the fallback scan`() {
        val gatedCallCount = mutableListOf<Unit>()
        val gated = object : PackoutStrategy {
            override val priority = 1
            override val name = "GATED"
            override fun pack(context: PackoutContext): PackoutResult? {
                gatedCallCount.add(Unit)
                return null
            }
        }
        val builtin = object : PackoutStrategy {
            override val priority = Int.MAX_VALUE
            override val name = "ONE_TO_ONE"
            override fun pack(context: PackoutContext): PackoutResult =
                PackoutResult(
                    shippingUnits = listOf(
                        PlannedShippingUnit(type = context.type, weight = context.weight, unitLoadId = null, lines = emptyList()),
                    ),
                    complete = true,
                )
        }
        val instance = mockk<Instance<PackoutStrategy>>()
        every { instance.iterator() } answers { mutableListOf(gated, builtin).iterator() }
        val resolver = PackoutStrategyResolver(instance)

        val result = resolver.resolve(
            PackoutContext(
                1, 2, 9, 1, BigDecimal("1.0"), "CARTON", "GATED",
                listOf(PackPick(11, 7, "SKU-7", BigDecimal(5), null, 3)),
            ),
        )

        assertThat(gatedCallCount).hasSize(1)
        assertThat(result.complete).isTrue
        assertThat(result.shippingUnits).hasSize(1)
    }
}

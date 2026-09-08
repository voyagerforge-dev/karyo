package com.karyo.fulfillment

import com.karyo.fulfillment.service.ShortfallStrategyResolver
import com.karyo.fulfillment.spi.ShortfallContext
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class ShortfallResolverTest {

    @Inject lateinit var resolver: ShortfallStrategyResolver

    // resolve() drives the built-in PartialShip, which writes a shortage report to the outbox —
    // a tx (in prod it runs inside confirmPick's @Transactional). Wrap the call to supply one.

    @Test
    fun `partial-ship built-in accepts the remainder`() {
        val resolution = QuarkusTransaction.requiringNew().call {
            resolver.resolve(
                ShortfallContext(
                    pickId = 1L, deliveryOrderId = 2L, deliveryOrderLineId = 3L, itemDataId = 4L,
                    remainder = BigDecimal(3), clientId = 1L, shortfallStrategyName = "PARTIAL_SHIP",
                ),
            )
        }
        assertThat(resolution.shortShipped).isEqualByComparingTo(BigDecimal(3))
    }

    @Test
    fun `an unknown strategy name falls through to the priority chain (built-in)`() {
        val resolution = QuarkusTransaction.requiringNew().call {
            resolver.resolve(
                ShortfallContext(
                    pickId = 1L, deliveryOrderId = 2L, deliveryOrderLineId = 3L, itemDataId = 4L,
                    remainder = BigDecimal(5), clientId = 1L, shortfallStrategyName = "NOT_REGISTERED",
                ),
            )
        }
        assertThat(resolution.shortShipped).isEqualByComparingTo(BigDecimal(5))
    }
}

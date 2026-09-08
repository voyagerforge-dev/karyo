package com.karyo.fulfillment

import com.karyo.fulfillment.service.PickOrderGroupingResolver
import com.karyo.fulfillment.spi.GroupingRequest
import com.karyo.fulfillment.spi.PlannedPick
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@QuarkusTest
class GroupingResolverTest {

    @Inject lateinit var resolver: PickOrderGroupingResolver

    @Test
    fun `discrete grouping yields one group holding all planned picks`() {
        val req = GroupingRequest(
            deliveryOrderId = 1L, deliveryOrderNumber = "ORD-1", clientId = 1L,
            plannedPicks = listOf(
                PlannedPick(10L, 100L, "SKU-A", null, 1000L, BigDecimal("5")),
                PlannedPick(11L, 101L, "SKU-B", null, 1001L, BigDecimal("3")),
            ),
        )
        val result = resolver.resolve(req)
        assertThat(result.groups).hasSize(1)
        assertThat(result.groups.first().picks).hasSize(2)
    }
}

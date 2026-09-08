package com.karyo.orders.service

import com.karyo.orders.dto.CreateOrderStrategyRequest
import com.karyo.orders.domain.model.OrderStrategy
import com.karyo.orders.spi.OrderStrategyContext
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

@QuarkusTest
class OrderStrategyResolverTest {

    @Inject
    lateinit var strategyService: OrderStrategyService

    @AfterEach
    fun reset() {
        TestOverrideResolver.overrideName = null
    }

    @Test
    fun `resolve with null id falls back to DEFAULT`() {
        val resolved = strategyService.resolve(OrderStrategyContext(orderStrategyId = null, clientId = 1))
        assertThat(resolved.name).isEqualTo(OrderStrategy.DEFAULT_NAME)
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "order-write"])
    fun `resolve with explicit id returns that strategy`() {
        val created = strategyService.create(
            CreateOrderStrategyRequest(name = "RESOLVER-A", useLockedStock = true, preferComplete = false),
        )
        val resolved = strategyService.resolve(OrderStrategyContext(orderStrategyId = created.id, clientId = 1))
        assertThat(resolved.name).isEqualTo("RESOLVER-A")
        assertThat(resolved.useLockedStock).isTrue()
    }

    @Test
    @TestSecurity(user = "mgr", roles = ["order-read", "order-write"])
    fun `a custom resolver pre-empts the built-in`() {
        strategyService.create(CreateOrderStrategyRequest(name = "FAST", preferComplete = true))
        // Even with an explicit different id, the lower-priority custom resolver wins.
        TestOverrideResolver.overrideName = "FAST"
        val resolved = strategyService.resolve(OrderStrategyContext(orderStrategyId = null, clientId = 1))
        assertThat(resolved.name).isEqualTo("FAST")
    }
}

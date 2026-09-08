package com.karyo.orders.exception

import com.karyo.orders.spi.ConcurrentStateChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D2 (defect-burndown-7): the streaming engine matches the release race structurally. */
class OrderExceptionContractTest {

    @Test
    fun `InvalidTransition is a ConcurrentStateChange so paid modules can match it without a core dependency`() {
        val e: Throwable = OrderException.InvalidTransition(1L, 100, 300)
        assertThat(e).isInstanceOf(ConcurrentStateChange::class.java)
    }
}

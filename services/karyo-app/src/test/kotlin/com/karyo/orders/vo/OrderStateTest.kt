package com.karyo.orders.vo

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure unit test for the OrderState transition rules: forward-only matrix,
 * gated CANCELED allowance (pre-PICKED only), and the sanctioned
 * PENDING -> PROCESSABLE/RESERVED retry hop.
 */
class OrderStateTest {

    @Test
    fun `all 18 states carry the exact myWMS codes`() {
        val expected = mapOf(
            OrderState.UNDEFINED to 0,
            OrderState.CREATED to 50,
            OrderState.RELEASED to 100,
            OrderState.PAUSE to 200,
            OrderState.PROCESSABLE to 300,
            OrderState.RESERVED to 400,
            OrderState.STARTED to 500,
            OrderState.PENDING to 550,
            OrderState.PICKED to 600,
            OrderState.PACKING to 640,
            OrderState.PACKED to 650,
            OrderState.SHIPPING to 670,
            OrderState.SHIPPED to 680,
            OrderState.FINISHED to 700,
            OrderState.FAILED to 710,
            OrderState.CANCELED to 800,
            OrderState.POSTPROCESSED to 900,
            OrderState.DELETABLE to 1000,
        )
        assertThat(OrderState.entries).hasSize(18)
        expected.forEach { (state, code) -> assertThat(state.code).isEqualTo(code) }
    }

    @Test
    fun `numerically forward transitions are allowed for non-CANCELED targets`() {
        for (source in OrderState.entries) {
            for (target in OrderState.entries) {
                if (target == OrderState.CANCELED || target.code <= source.code) continue
                assertThat(source.canAdvanceTo(target))
                    .withFailMessage("$source -> $target should be allowed (forward)")
                    .isTrue()
            }
        }
    }

    @Test
    fun `backward transitions are rejected except the sanctioned PENDING retry hop`() {
        for (source in OrderState.entries) {
            for (target in OrderState.entries) {
                if (target.code >= source.code) continue
                val sanctioned = source == OrderState.PENDING &&
                    (target == OrderState.PROCESSABLE || target == OrderState.RESERVED)
                assertThat(source.canAdvanceTo(target))
                    .withFailMessage("$source -> $target expected sanctioned=$sanctioned")
                    .isEqualTo(sanctioned)
            }
        }
    }

    @Test
    fun `same-state transition is rejected`() {
        for (state in OrderState.entries) {
            assertThat(state.canAdvanceTo(state)).isFalse()
        }
    }

    @Test
    fun `CANCELED is allowed only from pre-PICKED states`() {
        val cancellable = setOf(
            OrderState.UNDEFINED, OrderState.CREATED, OrderState.RELEASED, OrderState.PAUSE,
            OrderState.PROCESSABLE, OrderState.RESERVED, OrderState.STARTED, OrderState.PENDING,
        )
        for (source in OrderState.entries) {
            assertThat(source.canAdvanceTo(OrderState.CANCELED))
                .withFailMessage("$source -> CANCELED expected ${source in cancellable}")
                .isEqualTo(source in cancellable)
        }
    }

    @Test
    fun `PENDING retry hop allows PROCESSABLE and RESERVED but nothing else backward`() {
        assertThat(OrderState.PENDING.canAdvanceTo(OrderState.PROCESSABLE)).isTrue()
        assertThat(OrderState.PENDING.canAdvanceTo(OrderState.RESERVED)).isTrue()
        assertThat(OrderState.PENDING.canAdvanceTo(OrderState.RELEASED)).isFalse()
        assertThat(OrderState.PENDING.canAdvanceTo(OrderState.CREATED)).isFalse()
        assertThat(OrderState.PENDING.canAdvanceTo(OrderState.PAUSE)).isFalse()
    }

    @Test
    fun `fromCode resolves known codes and rejects unknown ones`() {
        assertThat(OrderState.fromCode(550)).isEqualTo(OrderState.PENDING)
        assertThat(OrderState.fromCode(0)).isEqualTo(OrderState.UNDEFINED)
        assertThatThrownBy { OrderState.fromCode(42) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

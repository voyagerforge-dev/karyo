package com.karyo.stocktaking

import com.karyo.stocktaking.vo.CountLineState
import com.karyo.stocktaking.vo.CountOrderState
import com.karyo.stocktaking.vo.CountSessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CountStateVoTest {
    @Test
    fun `order state advances forward and allows cancel before finished`() {
        assertThat(CountOrderState.GENERATED.canAdvanceTo(CountOrderState.COUNTED)).isTrue
        assertThat(CountOrderState.COUNTED.canAdvanceTo(CountOrderState.FINISHED)).isTrue
        assertThat(CountOrderState.COUNTED.canAdvanceTo(CountOrderState.CANCELLED)).isTrue
        assertThat(CountOrderState.FINISHED.canAdvanceTo(CountOrderState.CANCELLED)).isFalse
        assertThat(CountOrderState.COUNTED.canAdvanceTo(CountOrderState.GENERATED)).isFalse
        assertThat(CountOrderState.fromCode(500)).isEqualTo(CountOrderState.COUNTED)
    }

    @Test
    fun `session + line states resolve`() {
        assertThat(CountSessionState.OPEN.canAdvanceTo(CountSessionState.CLOSED)).isTrue
        assertThat(CountLineState.PLANNED.canAdvanceTo(CountLineState.COUNTED)).isTrue
    }
}

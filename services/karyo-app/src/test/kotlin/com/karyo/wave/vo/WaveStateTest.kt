package com.karyo.wave.vo

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WaveStateTest {
    @Test fun `forward transitions allowed, backward refused`() {
        assertTrue(WaveState.PLANNED.canAdvanceTo(WaveState.RELEASED))
        assertTrue(WaveState.RELEASED.canAdvanceTo(WaveState.PICKING))
        assertFalse(WaveState.PICKING.canAdvanceTo(WaveState.RELEASED))
        assertFalse(WaveState.COMPLETED.canAdvanceTo(WaveState.CANCELLED))
        assertFalse(WaveState.CANCELLED.canAdvanceTo(WaveState.PICKING))
    }
    @Test fun `cancel allowed from any pre-completed state`() {
        listOf(WaveState.PLANNED, WaveState.RELEASED, WaveState.PICKING, WaveState.CONSOLIDATING)
            .forEach { assertTrue(it.canAdvanceTo(WaveState.CANCELLED), "$it") }
    }
    @Test fun `skipping intermediate states is allowed`() {
        assertTrue(WaveState.RELEASED.canAdvanceTo(WaveState.CONSOLIDATING))
    }
}

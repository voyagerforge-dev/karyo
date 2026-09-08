package com.karyo.forecasting

import com.karyo.forecasting.math.NormalZTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NormalZTableTest {
    @Test fun `exact tabulated points`() {
        assertEquals(0.0, NormalZTable.zFor(0.50), 1e-9)
        assertEquals(1.6449, NormalZTable.zFor(0.95), 1e-9)
        assertEquals(3.0902, NormalZTable.zFor(0.999), 1e-9)
    }

    @Test fun `clamps below and above the table`() {
        assertEquals(0.0, NormalZTable.zFor(0.10), 1e-9)     // <= first level
        assertEquals(3.0902, NormalZTable.zFor(0.9999), 1e-9) // >= last level
    }

    @Test fun `linear interpolation between points`() {
        // midway between 0.90 (1.2816) and 0.95 (1.6449) at serviceLevel 0.925
        val expected = 1.2816 + 0.5 * (1.6449 - 1.2816)
        assertEquals(expected, NormalZTable.zFor(0.925), 1e-9)
    }
}

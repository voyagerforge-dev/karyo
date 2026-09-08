package com.karyo.reporting

import com.karyo.reporting.service.KpiFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KpiFormatTest {
    @Test fun `percent formats one decimal`() = assertEquals("98.4%", KpiFormat.percent(0.9840))
    @Test fun `hours formats one decimal with h`() = assertEquals("6.2h", KpiFormat.hours(6.23))
    @Test fun `perDay formats thousands`() = assertEquals("1,240/day", KpiFormat.perDay(1240.0))
    @Test fun `signed delta percent`() {
        assertEquals("+0.6%", KpiFormat.deltaPercent(0.984, 0.978))
        assertEquals("-1.0%", KpiFormat.deltaPercent(0.97, 0.98))
    }
    @Test fun `tone higher-is-good vs lower-is-good`() {
        assertEquals("up", KpiFormat.tone(higherIsGood = true, current = 5.0, prior = 4.0))
        assertEquals("warning", KpiFormat.tone(higherIsGood = true, current = 4.0, prior = 5.0))
        assertEquals("up", KpiFormat.tone(higherIsGood = false, current = 4.0, prior = 5.0)) // cycle time down = good
    }
}

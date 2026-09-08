package com.karyo.forecasting

import com.karyo.forecasting.spi.DailyDemand
import com.karyo.forecasting.spi.DemandHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ForecastContractTest {
    @Test
    fun `demand history constructs`() {
        val h = DemandHistory("SKU-A", listOf(DailyDemand(LocalDate.now(), 5.0)), 90)
        assertEquals("SKU-A", h.sku)
        assertEquals(1, h.daily.size)
    }
}

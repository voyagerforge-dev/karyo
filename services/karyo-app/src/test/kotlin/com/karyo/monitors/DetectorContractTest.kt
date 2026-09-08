package com.karyo.monitors

import com.karyo.monitors.spi.DetectionContext
import com.karyo.monitors.spi.Finding
import com.karyo.monitors.spi.MonitorRuntimeConfig
import com.karyo.monitors.vo.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectorContractTest {
    @Test
    fun `finding and context construct with expected fields`() {
        val ctx = DetectionContext(
            clientId = 1L,
            config = MonitorRuntimeConfig("expiry-risk", 7.0, Severity.HIGH, "<"),
        )
        assertEquals("expiry-risk", ctx.config.monitorKey)
        val f = Finding("SKU X", "expires soon", "cycle out", 3.0)
        assertTrue(f.observedValue == 3.0)
    }
}

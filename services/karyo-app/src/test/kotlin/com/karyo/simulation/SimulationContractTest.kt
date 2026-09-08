package com.karyo.simulation

import com.karyo.simulation.dto.ReorderSimResponseDto
import com.karyo.simulation.dto.ReorderSimSummaryDto
import com.karyo.simulation.spi.PolicyOutcome
import com.karyo.simulation.spi.ReorderSimInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SimulationContractTest {
    @Test
    fun `value types construct`() {
        val input = ReorderSimInput(listOf(1.0, 2.0), leadTimeDays = 7, sBase = 3, sSug = 8, orderQty = 4)
        assertEquals(2, input.demandSeries.size)
        assertEquals(7, input.leadTimeDays)
        assertEquals(5, PolicyOutcome(5, 0.9, 3.0).stockoutDays)
        val resp = ReorderSimResponseDto(ReorderSimSummaryDto(0, 0, 0.0, 0.0), emptyList())
        assertEquals(0, resp.summary.skusSimulated)
    }
}

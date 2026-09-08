package com.karyo.reporting

import com.karyo.common.time.WarehouseZone
import com.karyo.reporting.service.KpiRange
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class KpiRangeTest {
    private val now = Instant.parse("2026-06-28T12:00:00Z")

    @Test fun `from parses codes and rejects junk`() {
        assertEquals(KpiRange.D30, KpiRange.from("30D"))
        assertNull(KpiRange.from("BOGUS"))
    }

    @Test fun `7D window is the trailing 7 days`() {
        val (start, end) = KpiRange.D7.window(now)
        assertEquals(now, end)
        assertEquals(7L, ChronoUnit.DAYS.between(start, end))
    }

    @Test fun `prior window is the equal-length window immediately before`() {
        val (start, end) = KpiRange.D7.window(now)
        val (pStart, pEnd) = KpiRange.D7.priorWindow(now)
        assertEquals(start, pEnd)
        assertEquals(ChronoUnit.SECONDS.between(start, end), ChronoUnit.SECONDS.between(pStart, pEnd))
    }

    // Warehouse-local day bucketing (docs/WORKLIST.md, kpi-warehouse-tz, 2026-08-17): the
    // exact UTC instant of "Jan 1" depends on the deployment's zone (WarehouseZone.ZONE ==
    // ZoneId.systemDefault()), so this asserts the zone-relative invariant -- Jan 1 at
    // midnight, IN THAT ZONE -- instead of a hardcoded UTC instant, so the test passes
    // identically regardless of the host's configured TZ.
    @Test fun `YTD starts at Jan 1 midnight in the warehouse zone`() {
        val (start, _) = KpiRange.YTD.window(now)
        val startInZone = start.atZone(WarehouseZone.ZONE)
        assertEquals(1, startInZone.dayOfYear)
        assertEquals(0, startInZone.hour)
        assertEquals(0, startInZone.minute)
        assertEquals(0, startInZone.second)
    }
}

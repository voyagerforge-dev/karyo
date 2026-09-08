package com.karyo.common.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Plain unit test (no `@QuarkusTest`, no Postgres) for [sqlDate]'s zone-injected day bucketing --
 * the mechanism behind the fix for "`KpiViewRepository` computes its query-window bounds in UTC"
 * (docs/WORKLIST.md, kpi-warehouse-tz, 2026-08-17) and, since this class + its module were
 * promoted to `karyo-common` the same day (demand-reader-tz), the same mechanism now shared by
 * `DemandReader`/`SimDemandReader`'s `toLocalDate` conversion. Proves that the same instant
 * buckets to a different SQL day depending on which zone it's converted in, which is exactly the
 * mismatch that made `KpiViewRepositoryTest`'s tenant-window tests flake between local midnight
 * and UTC midnight -- and, unfixed, would skew a demand observation written near local midnight
 * out of the forecasting/simulation history window.
 */
class WarehouseZoneTest {

    @Test
    fun `sqlDate buckets to different days in UTC vs Asia Kolkata near midnight UTC`() {
        // 2026-08-16T18:41:00Z is still 2026-08-16 in UTC, but 2026-08-17T00:11 in IST (UTC+5:30).
        val instant = Instant.parse("2026-08-16T18:41:00Z")

        val utcDay = sqlDate(instant, ZoneOffset.UTC)
        val istDay = sqlDate(instant, ZoneId.of("Asia/Kolkata"))

        assertEquals(java.sql.Date.valueOf("2026-08-16"), utcDay)
        assertEquals(java.sql.Date.valueOf("2026-08-17"), istDay)
        assertNotEquals(utcDay, istDay)
    }

    @Test
    fun `sqlDate defaults to WarehouseZone ZONE (the JVM default zone)`() {
        val instant = Instant.now()
        assertEquals(sqlDate(instant, WarehouseZone.ZONE), sqlDate(instant))
        assertEquals(ZoneId.systemDefault(), WarehouseZone.ZONE)
    }
}

package com.karyo.replenishment

import com.karyo.layout.spi.FixAssignmentLookup
import com.karyo.replenishment.dto.ReplenishmentScanResult
import com.karyo.replenishment.service.ReplenishmentScanConfig
import com.karyo.replenishment.service.ReplenishmentScheduler
import com.karyo.replenishment.service.ReplenishmentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Plain-unit-test pattern (no @QuarkusTest), matching `MonitorEvaluatorTest` — the scheduler's
 * dependencies are all interfaces/simple holders, so mockk + direct construction is enough and
 * avoids CDI/config-profile ceremony. [ReplenishmentScanConfig] is a plain constructor-injected
 * holder, so its `autoScanEnabled` value is set directly per test rather than via a
 * `QuarkusTestProfile`.
 *
 * This test proves ONLY [ReplenishmentScheduler]'s own loop/try-catch logic (auto-scan gate,
 * one-tenant-failure-doesn't-stop-the-rest) — `ReplenishmentService` is fully mocked, so it
 * cannot see anything downstream of `scan`. **It is deliberately not a tenant-correctness
 * regression guard** (Task 3 review CRITICAL-1 found and fixed a real ambient-`TenantContext`
 * bug two calls deeper than `scan` itself — see [ReplenishmentScheduler]'s KDoc "Correction"
 * section — which a mocked `ReplenishmentService` structurally cannot catch). That guard is
 * `ReplenishmentSchedulerIntegrationTest`, a real-bean `@QuarkusTest` that calls `runOnce()`
 * without priming `TenantContext`.
 */
class ReplenishmentSchedulerTest {
    private val service = mockk<ReplenishmentService>(relaxed = true)
    private val fixAssignmentLookup = mockk<FixAssignmentLookup>()

    private fun scheduler(enabled: Boolean) = ReplenishmentScheduler(
        service,
        fixAssignmentLookup,
        ReplenishmentScanConfig(autoScanEnabled = enabled, scanInterval = "10m"),
    )

    @Test
    fun `enabled runOnce scans every distinct tenant returned by the lookup`() {
        every { fixAssignmentLookup.clientIdsWithAssignments() } returns listOf(1L, 2L)
        every { service.scan(any()) } returns ReplenishmentScanResult(emptyList(), emptyList())

        scheduler(enabled = true).runOnce()

        verify(exactly = 1) { service.scan(1L) }
        verify(exactly = 1) { service.scan(2L) }
    }

    @Test
    fun `disabled runOnce never touches the lookup or the service`() {
        scheduler(enabled = false).runOnce()

        verify(exactly = 0) { fixAssignmentLookup.clientIdsWithAssignments() }
        verify(exactly = 0) { service.scan(any()) }
    }

    @Test
    fun `one tenant's scan throwing does not stop the remaining tenants from being scanned`() {
        every { fixAssignmentLookup.clientIdsWithAssignments() } returns listOf(1L, 2L, 3L)
        every { service.scan(1L) } throws IllegalStateException("boom")
        every { service.scan(2L) } returns ReplenishmentScanResult(emptyList(), emptyList())
        every { service.scan(3L) } returns ReplenishmentScanResult(emptyList(), emptyList())

        scheduler(enabled = true).runOnce()

        verify(exactly = 1) { service.scan(1L) }
        verify(exactly = 1) { service.scan(2L) }
        verify(exactly = 1) { service.scan(3L) }
    }
}

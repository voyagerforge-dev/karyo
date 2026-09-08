package com.karyo.replenishment.service

import com.karyo.layout.spi.FixAssignmentLookup
import io.quarkus.scheduler.Scheduled
import io.quarkus.scheduler.Scheduled.ConcurrentExecution
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * R11: opt-in per-tenant `@Scheduled` wrapper around the existing on-demand
 * [ReplenishmentService.scan]. This background scan is Karyo behavior, not a parity claim.
 * Public behavioral contract:
 * `docs/functional/replenishment.md#5-on-demand-and-scheduled-scans`.
 *
 * Defaults OFF ([ReplenishmentScanConfig.autoScanEnabled] = `false`): a tenant that never
 * opts in keeps the pre-R11 on-demand-only behavior (`POST /api/v1/replenishment/scan`)
 * unchanged.
 *
 * No `TenantContext` priming here, deliberately: `ReplenishmentService.scan` takes `clientId` as
 * an explicit parameter and never reads ambient `TenantContext` internally. This follows
 * `KeycloakEventPoller`'s plain-parameter pattern (clientId/tenant identity passed explicitly
 * through the call, not via request-scoped state) -- `MonitorEvaluator` used to write
 * `tenantContext.clientId = clientId` per tenant instead, but row 30 (defect-burndown-4) found
 * that write load-bearing for nothing and removed it, so all three schedulers now follow the
 * same explicit-`clientId` doctrine. The tenant set comes from
 * [FixAssignmentLookup.clientIdsWithAssignments] (deliberately unscoped) rather than a separate
 * tenant registry, mirroring `TenantEnumerator` in karyo-monitors-core.
 *
 * **Correction (Task 3 review CRITICAL-1):** an earlier version of this KDoc claimed nothing on
 * `scan`'s call graph ever touches `TenantContext`, reasoning that the `@RequestScoped` scope is
 * never *activated* on a `@Scheduled` executor thread. That premise was wrong two ways: (1) the
 * request scope IS active on a `@Scheduled` thread in this codebase (no `ContextNotActiveException`
 * is thrown) — it is simply never PRIMED with a real tenant, so any ambient read silently sees
 * `TenantContext`'s unassigned default (`clientId = 0`), not an exception; (2) `scan`'s call graph
 * is deeper than `scan` itself — `FixAssignmentLookup.listForReplenishment` (via
 * `FixAssignmentService.enrichStockAmount`) and `TaskService.createReplenishment` (via
 * `UnitLoadLookup.findById`) BOTH transitively read ambient `TenantContext` through their
 * single-arg SPI overloads. Both call sites now use explicit-`clientId` overloads instead
 * (`StockUnitLookup.findByItemDataId(itemDataId, clientId)` / `UnitLoadLookup.findById(unitLoadId,
 * clientId)`) — the scan graph triggered from this scheduler is explicitly-`clientId` end-to-end,
 * genuinely, not just at this class's own boundary. See
 * `ReplenishmentSchedulerIntegrationTest` for the real-bean regression guard this class's own
 * mocked unit test cannot provide (mocking `ReplenishmentService` hides everything downstream of
 * `scan`, which is exactly where this bug lived).
 *
 * Split-body ([scheduled] vs [runOnce]) so tests and any future manual trigger can call
 * `runOnce()` directly without waiting on the timer -- same shape as `MonitorEvaluator` /
 * `KeycloakEventPoller`.
 */
@ApplicationScoped
class ReplenishmentScheduler(
    private val service: ReplenishmentService,
    private val fixAssignmentLookup: FixAssignmentLookup,
    private val config: ReplenishmentScanConfig,
) {
    private val log = Logger.getLogger(ReplenishmentScheduler::class.java)

    @Scheduled(every = "{karyo.replenishment.scan-interval}", concurrentExecution = ConcurrentExecution.SKIP)
    fun scheduled() = runOnce()

    /** The scheduled body, directly callable from tests without waiting on the timer. */
    fun runOnce() {
        if (!config.autoScanEnabled) return
        for (clientId in fixAssignmentLookup.clientIdsWithAssignments()) {
            try {
                service.scan(clientId)
            } catch (e: Exception) {
                log.warnf(e, "replenishment auto-scan failed for client %d", clientId)
            }
        }
    }
}
